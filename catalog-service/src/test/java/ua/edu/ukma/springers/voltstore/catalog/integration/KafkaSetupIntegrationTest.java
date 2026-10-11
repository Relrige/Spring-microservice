package ua.edu.ukma.springers.voltstore.catalog.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.events.ProductCreatedEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Task 10: the service connects to the broker, declares its topic and can send and receive
@SpringBootTest
@Import({TestcontainersConfiguration.class, KafkaSetupIntegrationTest.TestListener.class})
class KafkaSetupIntegrationTest {
    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TestListener testListener;

    @Test
    void productEventsTopic_isDeclaredWithThreePartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, TopicDescription> topics = admin.describeTopics(List.of(KafkaTopics.PRODUCT_EVENTS))
                    .allTopicNames().get(10, TimeUnit.SECONDS);

            TopicDescription topic = topics.get(KafkaTopics.PRODUCT_EVENTS);
            assertThat(topic.partitions()).hasSize(3);
            assertThat(topic.partitions()).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
        }
    }

    @Test
    void messageSentWithKafkaTemplate_isReceivedByListener() throws Exception {
        ProductCreatedEvent event = ProductCreatedEvent.forProduct(UUID.randomUUID());
        String key = event.productId().toString();

        kafkaTemplate.send(KafkaTopics.PRODUCT_EVENTS, key, event).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(testListener.received).anySatisfy(record -> {
                    assertThat(record.key()).isEqualTo(key);
                    assertThat(record.value()).contains(event.eventId().toString());
                    // No Java type headers: consumers must not depend on the producer's class names
                    assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                }));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestListener {
        final BlockingQueue<ConsumerRecord<String, String>> received = new LinkedBlockingQueue<>();

        // Reads raw JSON strings, independent of the application's value deserializer
        @KafkaListener(topics = KafkaTopics.PRODUCT_EVENTS, groupId = "kafka-setup-test",
                properties = "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer")
        void listen(ConsumerRecord<String, String> record) {
            received.add(record);
        }
    }
}
