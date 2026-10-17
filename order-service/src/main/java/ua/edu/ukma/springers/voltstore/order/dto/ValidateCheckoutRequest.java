package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.validation.UniqueProductIds;

import java.util.List;

/** EP-ORD-01. The limits are those of Catalog's batch-get and Inventory's reservation (spec: Request limits). */
public record ValidateCheckoutRequest(
        @NotEmpty(message = "Items must not be empty")
        @Size(max = ProductCatalog.MAX_IDS, message = "At most " + ProductCatalog.MAX_IDS + " items are allowed")
        @UniqueProductIds
        List<@NotNull(message = "Item must not be null") @Valid OrderItemRequest> items
) {
}
