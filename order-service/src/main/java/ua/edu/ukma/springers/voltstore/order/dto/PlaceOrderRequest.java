package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.validation.UniqueProductIds;

import java.util.List;

/**
 * EP-ORD-02. There is deliberately no customer ID, email or price here: they come from the trusted headers and from
 * Catalog, and such fields in the body are ignored.
 */
public record PlaceOrderRequest(
        @NotEmpty(message = "Items must not be empty")
        @Size(max = ProductCatalog.MAX_IDS, message = "At most " + ProductCatalog.MAX_IDS + " items are allowed")
        @UniqueProductIds
        List<@NotNull(message = "Item must not be null") @Valid OrderItemRequest> items,

        @NotNull(message = "Delivery details are required")
        @Valid
        DeliveryDetailsRequest deliveryDetails
) {
}
