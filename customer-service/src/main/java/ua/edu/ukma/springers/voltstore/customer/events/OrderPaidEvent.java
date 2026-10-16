package ua.edu.ukma.springers.voltstore.customer.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Customer's own view of Order's fat {@code OrderPaid} (EP-CUST-EVT-01): only the fields needed to clear the cart.
 * Everything else (delivery details, items, totals) is ignored (tolerant reader). {@code customerId} is null for a
 * guest order; a payload without {@code orderId} breaks the contract and is a poison message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidEvent(UUID eventId, @NotNull UUID orderId, UUID customerId) {
    public static final String TYPE = "OrderPaid";
}
