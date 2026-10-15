package ua.edu.ukma.springers.voltstore.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param provider   which {@link PaymentGateway} adapter is active; only {@code mock} exists
 * @param timeout    how long one gateway call may take before its outcome counts as unknown (a real adapter's read
 *                   timeout; the mock waits this long before reporting the "lost answer")
 * @param maxRetries how often a call with an unknown outcome is repeated with the same idempotency key
 * @param retryDelay pause before each repeat (plus up to half of it as random jitter)
 */
@ConfigurationProperties(prefix = "payment.gateway")
public record GatewayProperties(
        @DefaultValue("mock") String provider,
        @DefaultValue("2s") Duration timeout,
        @DefaultValue("1") int maxRetries,
        @DefaultValue("300ms") Duration retryDelay
) {
}
