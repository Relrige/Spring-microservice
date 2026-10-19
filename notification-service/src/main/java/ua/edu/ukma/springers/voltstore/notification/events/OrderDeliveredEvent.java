package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Notification's own view of Delivery's {@code OrderDelivered} (EP-NOTIF-EVT-04), on {@code delivery.shipment-events}.
 * It has no email address: customer contact data is not Delivery's concern, so the recipient is resolved from the
 * read model at send time (spec D1). The contract is the Delivery spec's <i>Shipment Events</i> table.
 */
public record OrderDeliveredEvent(
        @NotNull UUID eventId,
        @NotNull UUID orderId,
        @NotBlank String ttn
) {
    public static final String TYPE = "OrderDelivered";
}
