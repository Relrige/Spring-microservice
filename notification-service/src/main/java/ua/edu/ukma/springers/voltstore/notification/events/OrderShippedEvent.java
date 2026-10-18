package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Notification's own view of Order's {@code OrderShipped} (EP-NOTIF-EVT-03). Order ships only with a TTN, so a
 * missing {@code ttn} is a broken contract (poison), not a case to work around.
 */
public record OrderShippedEvent(
        @NotNull UUID eventId,
        @NotNull UUID orderId,
        @NotBlank String ttn,
        @NotBlank @Email String customerEmail
) {
    public static final String TYPE = "OrderShipped";
}
