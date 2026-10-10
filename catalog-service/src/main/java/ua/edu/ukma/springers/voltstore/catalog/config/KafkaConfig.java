package ua.edu.ukma.springers.voltstore.catalog.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics owned by Catalog Service, declared as code. On startup Spring's KafkaAdmin creates missing topics
 * (broker auto-creation is disabled). If the broker is down, KafkaAdmin only logs an error and the
 * application still starts.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {
    static final int PRODUCT_EVENTS_PARTITIONS = 3;
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
}
