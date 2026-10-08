package ua.edu.ukma.springers.voltstore.authservice.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        // HS256 requires a key of at least 256 bits
        @NotNull @Size(min = 32) String secret,
        @NotNull Duration expiration
) {
}
