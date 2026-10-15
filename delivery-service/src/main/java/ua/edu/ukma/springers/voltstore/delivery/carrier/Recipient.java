package ua.edu.ukma.springers.voltstore.delivery.carrier;

import java.util.Objects;

/** Who receives the parcel: the recipient snapshot taken from {@code OrderPaid.deliveryDetails}. */
public record Recipient(String name, String phone, String address) {
    public Recipient {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(phone, "phone must not be null");
        Objects.requireNonNull(address, "address must not be null");
    }
}
