package ua.edu.ukma.springers.voltstore.apigateway.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        // HS256 requires a key of at least 256 bits; shared with auth-service
        @NotNull @Size(min = 32) String secret,
        // tolerated clock difference between auth-service and the gateway
        @NotNull @DefaultValue("30s") Duration leeway
) {

}
