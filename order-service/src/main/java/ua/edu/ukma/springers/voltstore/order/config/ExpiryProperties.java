package ua.edu.ukma.springers.voltstore.order.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The payment-window expiry job (EP-ORD-JOB-01).
 *
 * @param interval  how often the job runs
 * @param grace     how long after the window an order is still left alone; must be longer than the Payment read
 *                  timeout (checked at startup), so a charge started inside the window can always finish
 * @param batchSize how many expired orders one run handles, the oldest first
 */
@Validated
@ConfigurationProperties(prefix = "order.expiry")
public record ExpiryProperties(@NotNull Duration interval, @NotNull Duration grace, @Min(1) int batchSize) {
}
