package ua.edu.ukma.springers.voltstore.notification.support;

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
 * Plays Order Service and Delivery Service in integration tests: publishes raw JSON records with an
 * {@code eventType} header, and reads Notification's dead-letter topics.
 */
public class KafkaEvents implements AutoCloseable {
    public static final String ORDER_TOPIC = "order.order-events";
    public static final String DELIVERY_TOPIC = "delivery.shipment-events";
    public static final String ORDER_DLT = "notification.order.order-events.dlt";
    public static final String DELIVERY_DLT = "notification.delivery.shipment-events.dlt";

    // The source topics are owned by Order and Delivery, and the broker does not auto-create topics, so tests
    // declare them
    @TestConfiguration(proxyBeanMethods = false)
    public static class SourceTopics {
        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name(ORDER_TOPIC).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic deliveryEventsTopic() {
            return TopicBuilder.name(DELIVERY_TOPIC).partitions(3).replicas(1).build();
        }
    }

    private final String bootstrapServers;
    private final KafkaProducer<String, String> producer;

    public KafkaEvents(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    /** Sends a record keyed by {@code key}; {@code eventType} null sends no header. */
    public void send(String topic, UUID key, String eventType, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key.toString(), json);
        if (eventType != null) {
            record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        producer.send(record).get();
    }

    public void sendOrderEvent(UUID orderId, String eventType, String json) throws Exception {
        send(ORDER_TOPIC, orderId, eventType, json);
    }

    public void sendDeliveryEvent(UUID orderId, String eventType, String json) throws Exception {
        send(DELIVERY_TOPIC, orderId, eventType, json);
    }

    /** Values on the dead-letter topic with this key. */
    public List<String> deadLetters(String deadLetterTopic, UUID key) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(deadLetterTopic));
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
