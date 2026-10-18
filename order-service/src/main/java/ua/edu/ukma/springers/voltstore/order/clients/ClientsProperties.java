package ua.edu.ukma.springers.voltstore.order.clients;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Where the four services Order calls live, and how long a call may take. Retries and circuit breakers are
 * configured per service under {@code resilience4j.*} (instances {@code catalog}, {@code inventory}, {@code payment},
 * {@code delivery}).
 */
@Validated
@ConfigurationProperties(prefix = "clients")
public record ClientsProperties(
        @Valid @NotNull Endpoint catalog,
        @Valid @NotNull Endpoint inventory,
        @Valid @NotNull Endpoint payment,
        @Valid @NotNull Endpoint delivery
) {
    /**
     * Payment repeats a call whose outcome is unknown once (about 4.5 s in the worst case). If Order gave up earlier,
     * it would report "unavailable" for a charge Payment is still settling.
     */
    public static final Duration MIN_PAYMENT_READ_TIMEOUT = Duration.ofSeconds(6);

    public record Endpoint(@NotBlank String url, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    @AssertTrue(message = "clients.payment.read-timeout must be at least 6s (Payment's own worst case is about 4.5 s)")
    public boolean isPaymentReadTimeoutLongEnough() {
        return payment == null || payment.readTimeout() == null
                || payment.readTimeout().compareTo(MIN_PAYMENT_READ_TIMEOUT) >= 0;
    }
}
