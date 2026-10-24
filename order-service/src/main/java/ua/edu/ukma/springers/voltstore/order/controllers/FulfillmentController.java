package ua.edu.ukma.springers.voltstore.order.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.order.dto.FulfillmentQueueResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ShippedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.openapi.OrderStateProblem;
import ua.edu.ukma.springers.voltstore.order.openapi.Problem;
import ua.edu.ukma.springers.voltstore.order.services.FulfillmentService;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.order.openapi.OpenApiConfig.BEARER_AUTH;

/**
 * Inventory worker endpoints. Roles are enforced by {@code SecurityConfig}, where the queue's rule comes before
 * {@code GET /orders/{orderId}}.
 */
@Tag(name = "Fulfillment")
@RestController
@RequiredArgsConstructor
public class FulfillmentController {
    private final FulfillmentService fulfillment;

    /** EP-ORD-07: paid orders waiting for dispatch, oldest payment first, with their shipment status. */
    @Operation(summary = "View the fulfillment queue (EP-ORD-07)",
            description = "Role: INVENTORY_WORKER. Paid orders waiting for dispatch, oldest payment first. When "
                    + "Delivery cannot be asked, `deliveryDataAvailable` is `false` and the shipment fields are "
                    + "missing (which then does not mean \"no shipment\").",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The queue")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an INVENTORY_WORKER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping("/orders/fulfillment-queue")
    public FulfillmentQueueResponse queue() {
        return fulfillment.queue();
    }

    /** EP-ORD-08: the courier collected the parcel. Allowed only once Delivery has its TTN. */
    @Operation(summary = "Mark an order as shipped (EP-ORD-08)",
            description = "Role: INVENTORY_WORKER. Allowed only once Delivery has generated the TTN.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "Shipped")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an INVENTORY_WORKER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No such order",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The order is not awaiting dispatch (OrderStateProblem), "
            + "or its TTN is not generated yet (Problem)",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(oneOf = {OrderStateProblem.class, Problem.class})))
    @ApiResponse(responseCode = "503", description = "Delivery cannot be reached; nothing changed",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/orders/{orderId}/ship")
    public ShippedOrderResponse ship(@PathVariable UUID orderId) {
        return fulfillment.ship(orderId);
    }
}
