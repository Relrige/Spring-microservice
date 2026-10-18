package ua.edu.ukma.springers.voltstore.order.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The shared secret Order sends as {@code X-Internal-Token} on every call to another service. Order receives no
 * internal calls, so unlike the other services it only sends the token, it never checks it.
 */
@Validated
@ConfigurationProperties(prefix = "auth.internal")
public record InternalTokenProperties(
        @NotBlank @Size(min = 32) String token
) {
    // Never print the secret
    @Override
    public String toString() {
        return "InternalTokenProperties[token=***]";
    }
}
