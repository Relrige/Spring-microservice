package ua.edu.ukma.springers.voltstore.delivery.services;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * @param batchSize shipments claimed per run
 * @param lease     how far a claim moves {@code next_attempt_at} forward, so no other replica picks the shipment
 *                  while this attempt runs; longer than the slowest attempt
 * @param backoff   waits before the retries after the first, second, ... failed attempt; when they are used up the
 *                  shipment becomes {@code TTN_FAILED}
 */
@Validated
@ConfigurationProperties(prefix = "delivery.ttn-job")
public record TtnJobProperties(
        @Positive int batchSize,
        @NotNull Duration lease,
        @NotEmpty List<@NotNull Duration> backoff
) {
}
