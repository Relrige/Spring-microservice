package ua.edu.ukma.springers.voltstore.catalog.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param url            base URL of Inventory Service (INVENTORY_SERVICE_URL)
 * @param connectTimeout time to establish the TCP connection
 * @param readTimeout    time to wait for the response
 * @param maxRetries     extra attempts after a transient failure (connection error, timeout, 5xx); 0 = no retry
 * @param retryDelay     wait before a retry (plus up to 50% random jitter)
 */
@Validated
@ConfigurationProperties(prefix = "clients.inventory")
public record InventoryClientProperties(
        @NotBlank String url,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @PositiveOrZero int maxRetries,
        @NotNull Duration retryDelay
) {
}
