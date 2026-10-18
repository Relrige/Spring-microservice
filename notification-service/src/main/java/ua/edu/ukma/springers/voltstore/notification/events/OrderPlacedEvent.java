package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Notification's own view of Order's {@code OrderPlaced} (EP-NOTIF-EVT-01): only the fields it reads; others
 * ({@code customerId}, {@code timestamp}) are ignored. A payload that breaks a constraint is a poison message.
 */
public record OrderPlacedEvent(
        @NotNull UUID eventId,
        @NotNull UUID orderId,
        @NotBlank @Email String customerEmail,
        @NotNull @PositiveOrZero BigDecimal totalAmount,
        @NotBlank String currency,
        @NotNull Instant reservationExpiresAt
) {
    public static final String TYPE = "OrderPlaced";
}
