package ua.edu.ukma.springers.voltstore.delivery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the outbox relay and cleanup (OutboxRelay)
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
