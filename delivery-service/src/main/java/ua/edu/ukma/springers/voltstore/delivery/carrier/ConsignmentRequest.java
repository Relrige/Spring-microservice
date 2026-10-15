package ua.edu.ukma.springers.voltstore.delivery.carrier;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** What the carrier needs to register one parcel. */
public record ConsignmentRequest(UUID orderId, String carrier, Recipient recipient, Parcel parcel,
                                 BigDecimal declaredValue, String currency) {
    public ConsignmentRequest {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(parcel, "parcel must not be null");
        Objects.requireNonNull(declaredValue, "declaredValue must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
