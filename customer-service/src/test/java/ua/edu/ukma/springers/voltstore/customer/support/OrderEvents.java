package ua.edu.ukma.springers.voltstore.customer.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays Order Service in integration tests: publishes {@code OrderPaid} with the payload from the Event Catalogue,
 * and reads Customer's dead-letter topic.
 */
public class OrderEvents implements AutoCloseable {
    public static final String TOPIC = "order.order-events";
    public static final String DEAD_LETTER_TOPIC = "customer.order.order-events.dlt";

    // Order's topic: owned by Order, and the broker does not auto-create topics, so the tests declare it
    @TestConfiguration(proxyBeanMethods = false)
    public static class SourceTopic {
        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }
    }

    private final String bootstrapServers;
    private final KafkaProducer<String, String> producer;

    public OrderEvents(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    /** Sends a record keyed by {@code key}; {@code eventType} null sends no header. */
    public void send(UUID key, String eventType, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key.toString(), json);
        if (eventType != null) {
            record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        producer.send(record).get();
    }

    /** The fat OrderPaid of the Event Catalogue for one product nobody has in the cart. */
    public static String orderPaid(UUID orderId, UUID customerId) {
        return orderPaid(orderId, customerId, List.of(UUID.randomUUID()));
    }

    /** The fat OrderPaid of the Event Catalogue with these ordered products; {@code customerId} null is a guest order. */
    public static String orderPaid(UUID orderId, UUID customerId, List<UUID> productIds) {
        String customer = customerId == null ? "null" : "\"" + customerId + "\"";
        String items = productIds.stream()
                .map(productId -> "{\"productId\":\"" + productId + "\",\"quantity\":2}")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"eventId":"%s","orderId":"%s","customerId":%s,"customerEmail":"user@example.com",
                 "deliveryDetails":{"name":"Ivan Petrenko","phone":"+380501234567","address":"Kyiv, branch 12",
                                    "carrier":"NOVA_POSHTA"},
                 "items":[%s],"totalAmount":1500.00,"currency":"UAH",
                 "paidAt":"2026-10-15T12:00:00Z"}
                """.formatted(UUID.randomUUID(), orderId, customer, items);
    }

    /** Values on Customer's dead-letter topic with this key. */
    public List<String> deadLetters(UUID key) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            List<String> values = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.toString().equals(record.key())) {
                        values.add(record.value());
                    }
                }
            }
            return values;
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
