package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Notification's own view of Order's fat {@code OrderPaid} (EP-NOTIF-EVT-02). {@code deliveryDetails} (personal
 * data) and {@code items} are deliberately not mapped, so they can never end up in a log line or the database.
 */
public record OrderPaidEvent(
        @NotNull UUID eventId,
        @NotNull UUID orderId,
        @NotBlank @Email String customerEmail,
        @NotNull @PositiveOrZero BigDecimal totalAmount,
        @NotBlank String currency,
        @NotNull Instant paidAt
) {
    public static final String TYPE = "OrderPaid";
}
