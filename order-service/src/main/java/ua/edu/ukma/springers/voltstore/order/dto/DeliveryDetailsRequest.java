package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ua.edu.ukma.springers.voltstore.order.validation.OfferedCarrier;

/**
 * Recipient and carrier entered at checkout (EP-ORD-02). The sizes are the column sizes of {@code orders}. Personal
 * data: the generated {@code toString()} is replaced so it is never logged by accident.
 */
public record DeliveryDetailsRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must be at most 200 characters")
        String name,

        // E.164: a plus, a country code not starting with 0, at most 15 digits in all
        @NotBlank(message = "Phone is required")
        @Pattern(regexp = "\\+[1-9]\\d{7,14}", message = "Phone must be in international format, e.g. +380501234567")
        String phone,

        @NotBlank(message = "Address is required")
        @Size(max = 500, message = "Address must be at most 500 characters")
        String address,

        @NotBlank(message = "Carrier is required")
        @OfferedCarrier
        String carrier
) {
    @Override
    public String toString() {
        return "DeliveryDetailsRequest[carrier=" + carrier + "]";
    }
}
