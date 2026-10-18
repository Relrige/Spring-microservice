package ua.edu.ukma.springers.voltstore.customer.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Customer's own view of Order's fat {@code OrderPaid} (EP-CUST-EVT-01): only the fields needed to take the ordered
 * products out of the cart. Everything else (delivery details, totals) is ignored (tolerant reader).
 * {@code customerId} is null for a guest order; a payload without {@code orderId} or {@code items} breaks the
 * contract and is a poison message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidEvent(UUID eventId, @NotNull UUID orderId, UUID customerId,
                             @NotNull List<@NotNull @Valid Item> items) {
    public static final String TYPE = "OrderPaid";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(@NotNull UUID productId) {
    }

    public List<UUID> productIds() {
        return items.stream().map(Item::productId).toList();
    }
}
