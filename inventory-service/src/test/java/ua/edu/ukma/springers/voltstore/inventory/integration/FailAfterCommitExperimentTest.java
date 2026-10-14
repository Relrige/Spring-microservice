package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Fault-tolerance experiment: the consumer fails after its database transaction committed but before Kafka
 * learned the record was processed (as if it crashed before committing the offset). The record is delivered
 * again (here through the retry topic), and the Idempotent Consumer must turn that second delivery into a no-op:
 * exactly one business effect.
 */
@SpringBootTest(properties = "inventory.experiments.fail-after-commit=true")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, FailAfterCommitExperimentTest.SourceTopic.class})
@ExtendWith(OutputCaptureExtension.class)
class FailAfterCommitExperimentTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class SourceTopic {
        @Bean
        NewTopic productEventsTopic() {
            return TopicBuilder.name("catalog.product-events").partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private StockItemRepository stockItemRepository;

    @Test
    void failureAfterCommit_isRedeliveredAndSkipped_soTheEffectHappensOnce(CapturedOutput output) throws Exception {
        UUID productId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        try (KafkaProducer<String, String> catalog = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            ProducerRecord<String, String> record = new ProducerRecord<>("catalog.product-events", productId.toString(),
                    "{\"eventId\":\"" + eventId + "\",\"productId\":\"" + productId + "\",\"timestamp\":\"2026-10-12T10:00:00Z\"}");
            record.headers().add(KafkaTopics.EVENT_TYPE_HEADER, "ProductCreated".getBytes(StandardCharsets.UTF_8));
            catalog.send(record).get();
        }

        // 1st delivery: committed, then the simulated failure; 2nd delivery (retry topic): duplicate, skipped
        await().atMost(Duration.ofSeconds(30)).until(() ->
                output.getAll().contains("Duplicate ProductCreated eventId=" + eventId));

        assertThat(output.getAll()).contains("EXPERIMENT: simulated consumer failure after commit, eventId=" + eventId);
        verify(stockItemRepository, times(1)).insertIfAbsent(eq(productId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_items WHERE product_id = ?", Integer.class, productId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }
}
