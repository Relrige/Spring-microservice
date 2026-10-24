package ua.edu.ukma.springers.voltstore.order.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import ua.edu.ukma.springers.voltstore.order.exceptions.PoisonMessageException;

import java.util.Map;

// Producer side as in Inventory, consumer side (dead letters, retries) as in Delivery
@Slf4j
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {
    static final String DEAD_LETTER_TEMPLATE = "deadLetterKafkaTemplate";

    /**
     * Order owns and declares its topic. Three partitions, as Inventory's and Delivery's topics: the key is the
     * orderId, so the events of one order stay in one partition and in order. One replica on the single local broker.
     */
    @Bean
    public NewTopic orderEventsTopic(@Value("${order.kafka.topics.order-events}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    // Dead-letter topic of the Delivery-events consumer, owned by Order; one partition: low volume, and arrival
    // order helps inspection. Delivery's topic itself is declared by Delivery, never here
    @Bean
    public NewTopic deliveryEventsDeadLetterTopic(@Value("${order.kafka.topics.delivery-events}") String source) {
        return TopicBuilder.name(KafkaTopics.deadLetterTopicOf(source)).partitions(1).replicas(1).build();
    }

    /**
     * The template the outbox relay sends with; typed explicitly, with Spring Boot's producer settings. Primary,
     * because a second template forwards consumed records to the dead-letter topic.
     */
    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        // Producer spans and the traceparent header (observability task 03); a template created here does not get
        // spring.kafka.template.observation-enabled
        template.setObservationEnabled(true);
        return template;
    }

    /**
     * Forwards failed records to the dead-letter topic. Consumed values are JSON text, so they are forwarded as the
     * same text: the JSON serializer of the default template would wrap them in quotes.
     */
    @Bean(DEAD_LETTER_TEMPLATE)
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    /**
     * Error handling of the Delivery-events listener (copied from Delivery); Spring Boot applies this bean to the
     * default container factory.
     * <ul>
     *   <li>Transient failures (the database briefly unavailable, an optimistic-lock conflict with a request
     *   changing the same order) are retried 4 times with exponential back-off (1 s, 2 s, 4 s, 8 s) by seeking back
     *   to the record, so later records of the partition wait and keep their order.</li>
     *   <li>A {@link PoisonMessageException} (not JSON, required field missing) is not retried.</li>
     *   <li>Either way the record then goes to {@code order.<source topic>.dlt} and its offset is committed, so one
     *   bad message never blocks the partition.</li>
     * </ul>
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            @Qualifier(DEAD_LETTER_TEMPLATE) KafkaTemplate<String, Object> textTemplate) {
        // Negative partition: let the producer choose, since a DLT has fewer partitions than its source
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(textTemplate,
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
