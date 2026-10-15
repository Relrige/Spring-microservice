package ua.edu.ukma.springers.voltstore.delivery.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJacksonJsonMessageConverter;

// Same structure and serialization as Inventory's KafkaConfig
@Configuration
public class KafkaConfig {

    // Delivery's own topic; Order's topic (consumed) is declared by Order, never here
    @Bean
    public NewTopic shipmentEventsTopic() {
        return TopicBuilder.name(KafkaTopics.SHIPMENT_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * The template for this service's own events (used by the outbox relay). Declared with its exact generic type,
     * so it can be injected (and spied on in tests) as {@code KafkaTemplate<String, Object>}; same producer factory
     * and settings from {@code spring.kafka.producer.*}. Primary, because task 10 adds a second template that
     * forwards consumed records to the dead-letter topic.
     */
    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
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
}
