package ua.edu.ukma.springers.voltstore.delivery.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.config.KafkaTopics;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, KafkaConnectionTest.TestListener.class})
class KafkaConnectionTest {
    record TestMessage(UUID orderId, String note) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestListener {
        private final BlockingQueue<TestMessage> received = new LinkedBlockingQueue<>();

        @Bean
        BlockingQueue<TestMessage> receivedMessages() {
            return received;
        }

        @KafkaListener(topics = KafkaTopics.SHIPMENT_EVENTS, groupId = "connection-test")
        void listen(TestMessage message) {
            received.add(message);
        }
    }

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private BlockingQueue<TestMessage> receivedMessages;

    @Test
    void shipmentEventsTopic_exists_withExactlyThreePartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var description = admin.describeTopics(List.of(KafkaTopics.SHIPMENT_EVENTS))
                    .allTopicNames().get(30, TimeUnit.SECONDS)
                    .get(KafkaTopics.SHIPMENT_EVENTS);

            assertThat(description.partitions()).hasSize(3);
        }
    }

    @Test
    void messageSentWithKafkaTemplate_isReceivedByListener() throws Exception {
        UUID orderId = UUID.randomUUID();

        kafkaTemplate.send(KafkaTopics.SHIPMENT_EVENTS, orderId.toString(), new TestMessage(orderId, "hello"))
                .get(30, TimeUnit.SECONDS);

        TestMessage message = receivedMessages.poll(30, TimeUnit.SECONDS);
        assertThat(message).isEqualTo(new TestMessage(orderId, "hello"));
    }
}
