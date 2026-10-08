package ua.edu.ukma.springers.voltstore.authservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth.admin")
public record AdminProperties(
        @NotBlank String email,
        @NotBlank String password
) {
}
