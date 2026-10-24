package ua.edu.ukma.springers.voltstore.inventory.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.kafka.retrytopic.RetryTopicBeanNames;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Slf4j
@Configuration
public class KafkaConfig {
    // Event names consumed by this service, as sent in the producers' eventType header
    static final String PRODUCT_CREATED = "ProductCreated";
    static final String PRODUCT_UPDATED = "ProductUpdated";
    static final String ORDER_SHIPPED = "OrderShipped";

    @Bean
    public NewTopic stockEventsTopic() {
        return TopicBuilder.name(KafkaTopics.STOCK_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    // Dead-letter topic of the OrderShipped consumer (blocking retries via the error handler below), owned by
    // Inventory; one partition: low volume, and arrival order helps inspection. The product events use @RetryableTopic,
    // which creates its own retry topics and DLT (see ProductEventsListener).
    @Bean
    public NewTopic orderEventsDeadLetterTopic(@Value("${inventory.kafka.topics.order-events}") String source) {
        return TopicBuilder.name(KafkaTopics.deadLetterTopicOf(source)).partitions(1).replicas(1).build();
    }

    /**
     * The template for this service's own events (JSON-serialized records, e.g. stock events). Declared explicitly
     * because a second KafkaTemplate bean (below) switches off Spring Boot's auto-configured one; same producer
     * factory and settings from {@code spring.kafka.producer.*}.
     */
    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Used by @RetryableTopic (found by this bean name) to forward failed records to the retry topics and the DLT.
     * Consumed values are JSON text, so they are forwarded as the same text: the JSON serializer of the default
     * template would wrap them in quotes, and the retry consumer could no longer read them.
     */
    @Bean(RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME)
    public KafkaTemplate<String, Object> retryTopicKafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
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
     * Error handling for listeners without @RetryableTopic (OrderShipped, test listeners); Spring Boot applies this
     * bean to the default container factory, and @RetryableTopic listeners get their own error handler instead.
     * <ul>
     *   <li>Transient failures (e.g. the database briefly unavailable) are retried 4 times with exponential
     *   back-off (1 s, 2 s, 4 s, 8 s) by seeking back to the record, so later records of the partition wait and
     *   keep their order.</li>
     *   <li>A payload that cannot be converted (not JSON, required field missing) fails in the listener adapter
     *   with a conversion exception, which Spring Kafka classifies as not retryable: no retries.</li>
     *   <li>Either way the record then goes to {@code inventory.<source topic>.dlt} and its offset is committed,
     *   so one bad message never blocks the partition.</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            @Qualifier(RetryTopicBeanNames.DEFAULT_KAFKA_TEMPLATE_BEAN_NAME) KafkaTemplate<String, Object> textTemplate) {
        // Values are read as JSON text (StringDeserializer), so they are republished as the same text
        // Negative partition: let the producer choose, since a DLT has fewer partitions than its source
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(textTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.deadLetterTopicOf(record.topic()), -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2);
        backOff.setMaxInterval(10_000);
        return new DefaultErrorHandler((record, ex) -> {
            log.error("Sending {} key={} partition={} offset={} to {}", record.topic(), record.key(),
                    record.partition(), record.offset(), KafkaTopics.deadLetterTopicOf(record.topic()), ex);
            recoverer.accept(record, ex);
        }, backOff);
    }

    /*
     * The source topics are per aggregate, so they may carry other event types (e.g. OrderPlaced, OrderCancelled
     * next to OrderShipped). Each listener keeps only its own type, by the eventType header, before the payload is
     * converted. Discarded records are acknowledged like processed ones.
     */

    // Both product event types share one listener container (see ProductEventsListener)
    @Bean
    public RecordFilterStrategy<String, String> productEventsOnly() {
        return onlyEventTypes(Set.of(PRODUCT_CREATED, PRODUCT_UPDATED));
    }

    @Bean
    public RecordFilterStrategy<String, String> orderShippedOnly() {
        return onlyEventType(ORDER_SHIPPED);
    }

    static RecordFilterStrategy<String, String> onlyEventType(String eventType) {
        return onlyEventTypes(Set.of(eventType));
    }

    static RecordFilterStrategy<String, String> onlyEventTypes(Set<String> eventTypes) {
        return record -> {
            Header header = record.headers().lastHeader(KafkaTopics.EVENT_TYPE_HEADER);
            boolean wanted = header != null
                    && eventTypes.contains(new String(header.value(), StandardCharsets.UTF_8));
            if (!wanted) {
                log.debug("Skipping {} record key={} offset={}: not one of {}", record.topic(), record.key(),
                        record.offset(), eventTypes);
            }
            // true = discard
            return !wanted;
        };
    }
}
