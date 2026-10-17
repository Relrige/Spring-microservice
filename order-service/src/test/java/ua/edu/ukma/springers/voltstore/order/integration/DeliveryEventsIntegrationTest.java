package ua.edu.ukma.springers.voltstore.order.integration;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.order.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.support.TopicProbe;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 15 end to end: records produced to Delivery's topic as Delivery produces them (JSON text, {@code eventType}
 * header, key = orderId), consumed by the real listener into PostgreSQL. "Processed" is checked by waiting until the
 * consumer group has committed every record, so a duplicate that changes nothing is still known to be handled.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class,
        DeliveryEventsIntegrationTest.DeliveryTopic.class})
class DeliveryEventsIntegrationTest {
    private static final String TTN = "20450012345678";

    /** Delivery declares its topic in production; here the test does, since the broker creates no topics. */
    @TestConfiguration(proxyBeanMethods = false)
    static class DeliveryTopic {
        @Bean
        NewTopic deliveryEventsTopic(@Value("${order.kafka.topics.delivery-events}") String topic) {
            return TopicBuilder.name(topic).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepository repository;

    @Value("${order.kafka.topics.delivery-events}")
    private String topic;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterEach
    void closeProducer() {
        producer.close();
    }

    @Test
    void ttnGenerated_movesAPaidOrderToPendingShipping_andADuplicateChangesNothing() {
        UUID orderId = paidOrder();

        send(orderId, "TTNGenerated", event(orderId));
        send(orderId, "TTNGenerated", event(orderId));
        awaitAllConsumed();

        assertThat(statusOf(orderId)).isEqualTo("PENDING_SHIPPING");
    }

    @Test
    void orderDelivered_movesAShippedOrderToDelivered_andADuplicateChangesNothing() {
        OrderEntity order = order();
        order.markPaid(Instant.now());
        order.markShipped();
        UUID orderId = repository.saveAndFlush(order).getId();

        send(orderId, "OrderDelivered", event(orderId));
        send(orderId, "OrderDelivered", event(orderId));
        awaitAllConsumed();

        assertThat(statusOf(orderId)).isEqualTo("DELIVERED");
    }

    @Test
    void ttnGenerated_afterACancellation_leavesTheOrderCancelling() {
        OrderEntity order = order();
        order.markPaid(Instant.now());
        order.requestCancellation(Instant.now());
        UUID orderId = repository.saveAndFlush(order).getId();

        send(orderId, "TTNGenerated", event(orderId));
        awaitAllConsumed();

        assertThat(statusOf(orderId)).isEqualTo("CANCELLATION_REQUESTED");
    }

    @Test
    void otherEventTypes_andUnknownOrders_areSkipped() {
        UUID orderId = paidOrder();

        send(orderId, "ShipmentRelabelled", event(orderId));
        UUID unknown = UUID.randomUUID();
        send(unknown, "TTNGenerated", event(unknown));
        awaitAllConsumed();

        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    @Test
    void aPoisonMessage_goesToTheDeadLetterTopic_andTheNextRecordIsProcessed() {
        UUID orderId = paidOrder();
        try (TopicProbe deadLetters = new TopicProbe(kafka.getBootstrapServers(),
                KafkaTopics.deadLetterTopicOf(topic))) {
            // Same key, so the same partition: the good record waits behind the bad ones
            String withoutTtn = "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"timestamp\":\"%s\"}"
                    .formatted(UUID.randomUUID(), orderId, Instant.now());
            send(orderId, "TTNGenerated", "not json at all");
            send(orderId, "TTNGenerated", withoutTtn);
            send(orderId, "TTNGenerated", event(orderId));

            // No retries for poison: the good record is through well before the first back-off (1 s) would end
            await().atMost(Duration.ofSeconds(20)).until(() -> "PENDING_SHIPPING".equals(statusOf(orderId)));
            List<ConsumerRecord<String, String>> dead = await().atMost(Duration.ofSeconds(20))
                    .until(deadLetters::poll, records -> records.size() >= 2);
            // Forwarded as the same text, not re-serialized
            assertThat(dead).extracting(ConsumerRecord::value).containsExactly("not json at all", withoutTtn);
            assertThat(deadLetters.partitionCount()).isEqualTo(1);
        }
    }

    private void send(UUID key, String eventType, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key.toString(), payload);
        record.headers().add(KafkaTopics.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        producer.send(record);
        producer.flush();
    }

    // Waits until the order-service group has committed the end offset of every partition of the topic
    private void awaitAllConsumed() {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                Set<TopicPartition> partitions = admin.describeTopics(List.of(topic)).allTopicNames().get()
                        .get(topic).partitions().stream()
                        .map(info -> new TopicPartition(topic, info.partition()))
                        .collect(Collectors.toSet());
                Map<TopicPartition, Long> ends = admin.listOffsets(partitions.stream()
                                .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest())))
                        .all().get().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets("order-service")
                        .partitionsToOffsetAndMetadata().get();
                return ends.entrySet().stream().allMatch(end -> end.getValue() == 0
                        || (committed.get(end.getKey()) != null
                        && committed.get(end.getKey()).offset() >= end.getValue()));
            });
        }
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private UUID paidOrder() {
        OrderEntity order = order();
        order.markPaid(Instant.now());
        return repository.saveAndFlush(order).getId();
    }

    private static String event(UUID orderId) {
        return "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"ttn\":\"%s\",\"timestamp\":\"%s\",\"extra\":\"ignored\"}"
                .formatted(UUID.randomUUID(), orderId, TTN, Instant.now());
    }

    private static OrderEntity order() {
        return OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 1)),
                "UAH", Instant.now(), Duration.ofMinutes(15));
    }
}
