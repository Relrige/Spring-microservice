package ua.edu.ukma.springers.voltstore.delivery.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * No retry settings on purpose: a failed call is retried by the next attempt of the TTN generation job.
 *
 * @param url            base URL of Inventory Service (INVENTORY_SERVICE_URL)
 * @param connectTimeout time to establish the TCP connection
 * @param readTimeout    time to wait for the response
 */
@Validated
@ConfigurationProperties(prefix = "clients.inventory")
public record InventoryClientProperties(
        @NotBlank String url,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
