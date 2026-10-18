package ua.edu.ukma.springers.voltstore.order.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.order.dto.FulfillmentQueueResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ShippedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.services.FulfillmentService;

import java.util.UUID;

/**
 * Inventory worker endpoints. Roles are enforced by {@code SecurityConfig}, where the queue's rule comes before
 * {@code GET /orders/{orderId}}.
 */
@RestController
@RequiredArgsConstructor
public class FulfillmentController {
    private final FulfillmentService fulfillment;

    /** EP-ORD-07: paid orders waiting for dispatch, oldest payment first, with their shipment status. */
    @GetMapping("/orders/fulfillment-queue")
    public FulfillmentQueueResponse queue() {
        return fulfillment.queue();
    }

    /** EP-ORD-08: the courier collected the parcel. Allowed only once Delivery has its TTN. */
    @PostMapping("/orders/{orderId}/ship")
    public ShippedOrderResponse ship(@PathVariable UUID orderId) {
        return fulfillment.ship(orderId);
    }
}
