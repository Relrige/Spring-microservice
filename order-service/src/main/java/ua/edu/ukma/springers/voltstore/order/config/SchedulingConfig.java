package ua.edu.ukma.springers.voltstore.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the outbox relay and cleanup (OutboxRelay); later the expiry and cancellation jobs (tasks 12 and 13)
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
