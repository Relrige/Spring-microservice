package ua.edu.ukma.springers.voltstore.inventory.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

@Slf4j
@Configuration
public class KafkaConfig {
    static final int MAX_RETRIES = 3;
    static final long INITIAL_BACK_OFF_MS = 1_000;
    static final double BACK_OFF_MULTIPLIER = 2.0;

    @Bean
    public NewTopic stockEventsTopic() {
        return TopicBuilder.name(KafkaTopics.STOCK_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * Spring Boot applies this converter to every @KafkaListener container. It parses the JSON text of
     * the record value into the type of the listener method parameter and ignores unknown properties.
     * The type is inferred from the method, never from a producer's type header.
     */
    @Bean
    public RecordMessageConverter jsonRecordMessageConverter() {
        return new StringJacksonJsonMessageConverter();
    }

    /**
     * Spring Boot applies this error handler to every @KafkaListener container.
     * <ul>
     *     <li>Retryable failures (e.g. the database is briefly unavailable): the record is redelivered after
     *     1 s, 2 s and 4 s. The partition waits meanwhile, so per-key order is kept.</li>
     *     <li>Not retryable (conversion/deserialization errors: malformed JSON, missing mandatory field):
     *     no retries, the record goes straight to the recoverer.</li>
     *     <li>Recoverer: log the record and skip it (its offset is committed), so a poison message never blocks
     *     the partition. A dead-letter topic replaces this together with the transactional outbox.</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        return errorHandler((consumerRecord, exception) -> log.error(
                "Kafka record skipped after failed processing: topic={} partition={} offset={} key={} value={}",
                consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(),
                consumerRecord.key(), consumerRecord.value(), exception));
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_BACK_OFF_MS);
        backOff.setMultiplier(BACK_OFF_MULTIPLIER);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
