package ua.edu.ukma.springers.voltstore.inventory.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth.internal")
public record InternalTokenProperties(
        @NotBlank @Size(min = 32) String token
) {
}
