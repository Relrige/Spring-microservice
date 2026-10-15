package ua.edu.ukma.springers.voltstore.delivery.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Delivery's own view of Order's fat {@code OrderPaid} (EP-DEL-EVT-01): only the fields a shipment needs. Other
 * fields ({@code customerId}, {@code customerEmail}, {@code paidAt}, ...) are ignored (tolerant reader). A payload
 * that breaks one of the constraints is a poison message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidEvent(
        UUID eventId,
        @NotNull UUID orderId,
        @NotNull @Valid DeliveryDetails deliveryDetails,
        @NotEmpty List<@NotNull @Valid Item> items,
        @NotNull @PositiveOrZero BigDecimal totalAmount,
        @NotBlank String currency
) {
    public static final String TYPE = "OrderPaid";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryDetails(@NotBlank String name, @NotBlank String phone, @NotBlank String address,
                                  @NotBlank String carrier) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(@NotNull UUID productId, @Positive int quantity) {
    }
}
