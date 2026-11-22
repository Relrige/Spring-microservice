package ua.edu.ukma.springers.voltstore.apigateway.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/** Request limits per client IP address; {@code authRoutes} get the strict {@code auth} limit. */
@Validated
@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @NotNull Set<String> authRoutes,
        @NotNull @Valid Limit auth,
        @NotNull @Valid Limit general
) {

    /** At most {@code requests} per {@code period}. */
    public record Limit(@Min(1) int requests, @NotNull Duration period) {
    }
}
