package ua.edu.ukma.springers.voltstore.catalog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		// Same broker settings that matter in production: topics must be declared, never auto-created
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
				.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
	}

}
