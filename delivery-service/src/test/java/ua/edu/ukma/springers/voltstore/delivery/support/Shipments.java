package ua.edu.ukma.springers.voltstore.delivery.support;

import ua.edu.ukma.springers.voltstore.delivery.carrier.Recipient;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Test data: shipments as the OrderPaid consumer would create them. */
public final class Shipments {
    public static final Recipient RECIPIENT = new Recipient("Olena Kovalenko", "+380501234567", "Kyiv, NP #12");

    private Shipments() {
    }

    /** A pending shipment with one unit of each product (one random product if none is given). */
    public static Shipment pending(UUID orderId, UUID... productIds) {
        return pending(orderId, Instant.now(), productIds);
    }

    /** As {@link #pending(UUID, UUID...)}, due for its first TTN attempt at {@code now}. */
    public static Shipment pending(UUID orderId, Instant now, UUID... productIds) {
        List<ShipmentItem> items = productIds.length == 0
                ? List.of(new ShipmentItem(UUID.randomUUID(), 1))
                : Arrays.stream(productIds).map(id -> new ShipmentItem(id, 1)).toList();
        return Shipment.pending(orderId, RECIPIENT, "NOVA_POSHTA", items, new BigDecimal("1500.00"), "UAH", now);
    }
}
