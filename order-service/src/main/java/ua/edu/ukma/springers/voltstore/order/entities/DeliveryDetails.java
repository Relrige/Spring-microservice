package ua.edu.ukma.springers.voltstore.order.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * Recipient and carrier as entered at checkout; copied unchanged into {@code OrderPaid} for Delivery. Personal data:
 * never logged, so {@link #toString()} is deliberately not generated.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryDetails {

    @Column(name = "delivery_name", nullable = false)
    private String name;

    @Column(name = "delivery_phone", nullable = false)
    private String phone;

    @Column(name = "delivery_address", nullable = false)
    private String address;

    @Column(name = "delivery_carrier", nullable = false)
    private String carrier;

    public DeliveryDetails(String name, String phone, String address, String carrier) {
        this.name = Objects.requireNonNull(name);
        this.phone = Objects.requireNonNull(phone);
        this.address = Objects.requireNonNull(address);
        this.carrier = Objects.requireNonNull(carrier);
    }
}
