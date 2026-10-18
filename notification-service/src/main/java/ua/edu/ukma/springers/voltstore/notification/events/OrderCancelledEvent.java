package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Notification's own view of Order's {@code OrderCancelled} (EP-NOTIF-EVT-05). {@code reason} is read as a string
 * (tolerant reader), so a reason Order adds later never fails deserialisation; see {@link #CUSTOMER_CANCELLED} and
 * {@link #EXPIRED}.
 */
public record OrderCancelledEvent(
        @NotNull UUID eventId,
        @NotNull UUID orderId,
        @NotBlank @Email String customerEmail,
        @NotBlank String reason
) {
    public static final String TYPE = "OrderCancelled";
    public static final String CUSTOMER_CANCELLED = "CUSTOMER_CANCELLED";
    public static final String EXPIRED = "EXPIRED";
}
