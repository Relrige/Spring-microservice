package ua.edu.ukma.springers.voltstore.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * Order owns and declares its topic. Three partitions, as Inventory's and Delivery's topics: the key is the
     * orderId, so the events of one order stay in one partition and in order. One replica on the single local broker.
     */
    @Bean
    public NewTopic orderEventsTopic(@Value("${order.kafka.topics.order-events}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    /** The template the outbox relay sends with; typed explicitly, with Spring Boot's producer settings. */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
