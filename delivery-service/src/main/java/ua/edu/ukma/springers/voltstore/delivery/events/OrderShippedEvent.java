package ua.edu.ukma.springers.voltstore.delivery.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Delivery's own view of Order's {@code OrderShipped} (EP-DEL-EVT-02): the courier collected the parcel. Other
 * payload fields, such as {@code customerEmail}, are ignored (tolerant reader). The {@code ttn} is informational:
 * the shipment's own TTN is used.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderShippedEvent(@NotNull UUID orderId, @Nullable String ttn) {
    public static final String TYPE = "OrderShipped";
}
