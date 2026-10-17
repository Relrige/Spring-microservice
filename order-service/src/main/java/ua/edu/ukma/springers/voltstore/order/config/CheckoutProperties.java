package ua.edu.ukma.springers.voltstore.order.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/**
 * Checkout settings (EP-ORD-02).
 *
 * @param paymentWindow how long a new order may be paid; its reservation is released after that (task 13)
 * @param carriers      the carriers a customer may choose; each must be one Delivery can register parcels with
 */
@Validated
@ConfigurationProperties(prefix = "order.checkout")
public record CheckoutProperties(@NotNull Duration paymentWindow, @NotEmpty Set<String> carriers) {
}
