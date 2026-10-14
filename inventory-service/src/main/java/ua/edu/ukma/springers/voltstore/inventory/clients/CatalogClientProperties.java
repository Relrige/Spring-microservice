package ua.edu.ukma.springers.voltstore.inventory.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param url            base URL of Catalog Service (CATALOG_SERVICE_URL)
 * @param connectTimeout time to establish the TCP connection
 * @param readTimeout    time to wait for the response
 */
@Validated
@ConfigurationProperties(prefix = "clients.catalog")
public record CatalogClientProperties(
        @NotBlank String url,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
