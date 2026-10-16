package ua.edu.ukma.springers.voltstore.customer.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import ua.edu.ukma.springers.voltstore.customer.exceptions.PoisonMessageException;

/**
 * Kafka wiring of the one consumer (Order's topic). Customer Service publishes no events of its own, so there is no
 * own topic and no outbox; the only records it produces are failed records forwarded to its dead-letter topic, with
 * Spring Boot's auto-configured {@link KafkaTemplate} (string serializers, see application.yml).
 */
@Slf4j
@Configuration
public class KafkaConfig {

    // Dead-letter topic of the Order-events consumer, owned by Customer; one partition: low volume, and arrival
    // order helps inspection. Order's topic itself is declared by Order, never here
    @Bean
    public NewTopic orderEventsDeadLetterTopic(@Value("${customer.kafka.topics.order-events}") String source) {
        return TopicBuilder.name(KafkaTopics.deadLetterTopicOf(source)).partitions(1).replicas(1).build();
    }

    /**
     * Error handling of the Order-events listener (as in Delivery and Inventory); Spring Boot applies this bean to the
     * default container factory.
     * <ul>
     *   <li>Transient failures (e.g. the database briefly unavailable) are retried 4 times with exponential
     *   back-off (1 s, 2 s, 4 s, 8 s) by seeking back to the record, so later records of the partition wait and
     *   keep their order.</li>
     *   <li>A {@link PoisonMessageException} (not JSON, required field missing) is not retried.</li>
     *   <li>Either way the record then goes to {@code customer.<source topic>.dlt} and its offset is committed,
     *   so one bad message never blocks the partition.</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Negative partition: let the producer choose, since a DLT has fewer partitions than its source
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.deadLetterTopicOf(record.topic()), -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2);
        backOff.setMaxInterval(10_000);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, ex) -> {
            log.error("Sending {} key={} partition={} offset={} to {}", record.topic(), record.key(),
                    record.partition(), record.offset(), KafkaTopics.deadLetterTopicOf(record.topic()), ex);
            recoverer.accept(record, ex);
        }, backOff);
        errorHandler.addNotRetryableExceptions(PoisonMessageException.class);
        return errorHandler;
    }
}
