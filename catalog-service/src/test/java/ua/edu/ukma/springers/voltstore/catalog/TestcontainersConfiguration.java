package ua.edu.ukma.springers.voltstore.catalog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

// PostgreSQL and Kafka on the versions used in docker-compose.yml and k8s/
@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresContainerConfiguration.class, KafkaContainerConfiguration.class})
public class TestcontainersConfiguration {
}
