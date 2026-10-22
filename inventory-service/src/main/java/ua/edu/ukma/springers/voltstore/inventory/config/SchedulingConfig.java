package ua.edu.ukma.springers.voltstore.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the outbox relay and cleanup (OutboxRelay), the processed-events cleanup (ProcessedEvents) and the product
// data backfill (ProductDataBackfill)
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
