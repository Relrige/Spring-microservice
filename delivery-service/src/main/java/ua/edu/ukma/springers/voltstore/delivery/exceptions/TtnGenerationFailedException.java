package ua.edu.ukma.springers.voltstore.delivery.exceptions;

import lombok.Getter;

import java.util.UUID;

/**
 * A manual TTN retry (EP-DEL-03) was rejected by Inventory or the carrier. {@code reason} is the stable value the
 * fulfillment queue shows, e.g. {@code packaging_data_missing:{productId}}, {@code stock_item_not_found:{productId}},
 * {@code inventory_unavailable} or {@code carrier_error: <message>}.
 */
@Getter
public class TtnGenerationFailedException extends RuntimeException {
    private final UUID orderId;
    private final String reason;

    public TtnGenerationFailedException(UUID orderId, String reason) {
        super("TTN generation failed for order " + orderId + ": " + reason);
        this.orderId = orderId;
        this.reason = reason;
    }
}
