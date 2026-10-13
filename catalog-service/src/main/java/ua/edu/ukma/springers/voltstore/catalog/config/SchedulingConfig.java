package ua.edu.ukma.springers.voltstore.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the outbox relay and cleanup jobs (OutboxRelay)
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
