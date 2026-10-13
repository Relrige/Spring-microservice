package ua.edu.ukma.springers.voltstore.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the processed-events cleanup (ProcessedEvents)
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
