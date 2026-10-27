package ua.edu.ukma.springers.voltstore.catalog.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Topics owned by Catalog Service, declared as code. On startup Spring's KafkaAdmin creates missing topics
 * (broker auto-creation is disabled). If the broker is down, KafkaAdmin only logs an error and the
 * application still starts.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {
    static final int PRODUCT_EVENTS_PARTITIONS = 3;
    static final int STOCK_EVENTS_PARTITIONS = 3;
    // The platform runs a single broker, so no topic can have more than one replica
    static final int REPLICATION_FACTOR = 1;

    /**
     * 3 partitions: with productId as the key, all events of one product land in the same partition
     * (so they stay ordered), while up to three consumers of one group can share the load.
     */
    @Bean
    public NewTopic productEventsTopic() {
        return TopicBuilder.name(KafkaTopics.PRODUCT_EVENTS)
                .partitions(PRODUCT_EVENTS_PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    /**
     * Owned by Inventory Service, which does not declare it yet. Declaring it here as well lets the consumer
     * start without "unknown topic" errors; once Inventory declares the same topic, both declarations are
     * identical and KafkaAdmin leaves the existing topic unchanged.
     */
    @Bean
    public NewTopic stockEventsTopic() {
        return TopicBuilder.name(KafkaTopics.STOCK_EVENTS)
                .partitions(STOCK_EVENTS_PARTITIONS)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    // Low-volume by nature; one partition keeps failed messages in arrival order for inspection
    @Bean
    public NewTopic stockEventsDeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.STOCK_EVENTS_DLT)
                .partitions(1)
                .replicas(REPLICATION_FACTOR)
                .build();
    }

    /**
     * Error handling for every listener (Spring Boot applies this bean to the default container factory).
     * <ul>
     *   <li>Transient failures (e.g. the database briefly unavailable) are retried 4 times with exponential
     *   back-off (1 s, 2 s, 4 s, 8 s) by seeking back to the failed record, so later records wait and the
     *   partition order is kept.</li>
     *   <li>Messages that can never succeed (undeserializable payloads, which Spring Kafka classifies as
     *   not retryable) skip the retries.</li>
     *   <li>Either way the record then goes to {@link KafkaTopics#STOCK_EVENTS_DLT} and its offset is
     *   committed, so one bad message never blocks the partition.</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ProducerFactory<Object, Object> producerFactory) {
        // Not beans: another KafkaTemplate bean would switch off Spring Boot's auto-configured one.
        // The raw bytes of an undeserializable message are republished as they were received.
        KafkaTemplate<Object, Object> bytesTemplate = new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        // Not the auto-configured template: its observation would add a second traceparent header, while the
        // recoverer already copies the failed record's one (observability task 03)
        templates.put(Object.class, new KafkaTemplate<>(producerFactory));

        // Negative partition: let the producer choose, since the DLT has fewer partitions than the source
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, ex) -> new TopicPartition(KafkaTopics.STOCK_EVENTS_DLT, -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2);
        backOff.setMaxInterval(10_000);
        return new DefaultErrorHandler((record, ex) -> {
            log.error("Sending {} key={} partition={} offset={} to {}",
                    record.topic(), record.key(), record.partition(), record.offset(), KafkaTopics.STOCK_EVENTS_DLT, ex);
            recoverer.accept(record, ex);
        }, backOff);
    }
}
