package ua.edu.ukma.springers.voltstore.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// Same broker version as docker-compose.yml; topics are never auto-created there either
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestContainerConfig {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }

}
