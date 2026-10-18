package ua.edu.ukma.springers.voltstore.order.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The jobs that finish work Order still owes another service: releasing orphan reservations and repeating refunds
 * (decision 2026-10-18). The refund back-off and the manual-attention limit are the cancellation's
 * ({@code order.cancellation.*}).
 *
 * @param interval            how often both jobs run
 * @param reservationSettle   age after which a reservation attempt without an order is released; longer than any
 *                            reservation call can take (Inventory read timeout × retries), so a late answer has
 *                            arrived by then
 * @param batchSize           rows per run, the oldest first
 */
@Validated
@ConfigurationProperties(prefix = "order.recovery")
public record RecoveryProperties(@NotNull Duration interval, @NotNull Duration reservationSettle,
                                 @Min(1) int batchSize) {
}
