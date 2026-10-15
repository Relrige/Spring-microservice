package ua.edu.ukma.springers.voltstore.delivery.carrier;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Behaviour of the in-process mock carrier, for demos and tests.
 *
 * @param failureRate   probability (0.0–1.0) that {@code register} or {@code cancel} throws {@link CarrierException}
 * @param latency       how long {@code register} and {@code cancel} take
 * @param deliveryDelay time between a handover and the delivery report
 */
@Validated
@ConfigurationProperties(prefix = "delivery.carrier.mock")
public record MockCarrierProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
        @NotNull Duration latency,
        @NotNull Duration deliveryDelay
) {
}
