package ua.edu.ukma.springers.voltstore.order.outbox;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param batchSize      events read per relay run
 * @param publishTimeout how long the relay waits for one broker acknowledgment before treating it as failed
 * @param maxRetryDelay  upper bound of the per-event exponential back-off after failed publishes
 * @param retention      how long published events are kept before cleanup deletes them
 */
@Validated
@ConfigurationProperties(prefix = "order.outbox")
public record OutboxProperties(
        @Positive int batchSize,
        @NotNull Duration publishTimeout,
        @NotNull Duration maxRetryDelay,
        @NotNull Duration retention
) {
}
