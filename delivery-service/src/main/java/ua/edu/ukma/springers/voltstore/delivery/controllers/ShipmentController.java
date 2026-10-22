package ua.edu.ukma.springers.voltstore.delivery.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.delivery.dto.RetryTtnResponse;
import ua.edu.ukma.springers.voltstore.delivery.dto.ShipmentStatusResponse;
import ua.edu.ukma.springers.voltstore.delivery.openapi.InternalApi;
import ua.edu.ukma.springers.voltstore.delivery.openapi.Problem;
import ua.edu.ukma.springers.voltstore.delivery.openapi.TtnGenerationFailedProblem;
import ua.edu.ukma.springers.voltstore.delivery.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.delivery.services.ManualTtnRetryService;
import ua.edu.ukma.springers.voltstore.delivery.services.ShipmentService;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.delivery.openapi.OpenApiConfig.BEARER_AUTH;

// Who may call which route is declared in SecurityConfig
@Tag(name = "Shipments")
@RestController
@RequestMapping("/delivery")
@RequiredArgsConstructor
public class ShipmentController {
    private final ShipmentService shipmentService;
    private final ManualTtnRetryService manualTtnRetryService;

    /**
     * EP-DEL-01 (internal, Order). {@code orderIds} is one comma-separated value, as in Catalog's batch-get; a
     * non-UUID element fails conversion → 400. Missing or empty and too many values are checked by the service.
     */
    @InternalApi
    @Operation(summary = "Get shipment statuses for orders (EP-DEL-01)",
            description = "One entry per distinct order ID. An order without a shipment yet has only `orderId`.")
    @ApiResponse(responseCode = "200", description = "The statuses")
    @ApiResponse(responseCode = "400", description = "Missing or empty `orderIds`, an empty element, more than 100 "
            + "distinct values, or a non-UUID value",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping("/shipments")
    public List<ShipmentStatusResponse> getShipmentStatuses(
            // Documented as required: without it the service answers 400 (the check is in the service, not in binding)
            @Parameter(required = true, description = "Comma-separated order IDs, at most 100 distinct")
            @RequestParam(required = false) List<UUID> orderIds) {
        return shipmentService.getStatuses(orderIds);
    }

    /**
     * EP-DEL-02 (internal, Order's cancellation saga): {@code 204} for every outcome except a parcel already handed
     * to the carrier ({@code 409}).
     */
    @InternalApi
    @Operation(summary = "Cancel an order's shipment (EP-DEL-02)",
            description = "Idempotent: 204 also when there is no shipment or it is already cancelled, and when the "
                    + "carrier cancellation fails (the failure is recorded).")
    @ApiResponse(responseCode = "204", description = "Cancelled, or nothing to cancel")
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The parcel was already handed to the carrier",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @DeleteMapping("/shipments/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelShipment(@PathVariable UUID orderId) {
        shipmentService.cancel(orderId);
    }

    /**
     * EP-DEL-03 (inventory worker, via the gateway): one synchronous TTN attempt for a {@code TTN_FAILED} shipment.
     * {@code 200 { ttn }}; {@code 404} no shipment; {@code 409} not {@code TTN_FAILED} or changed meanwhile;
     * {@code 424 { reason }} when Inventory or the carrier rejected the attempt.
     */
    @Operation(summary = "Retry TTN generation (EP-DEL-03)",
            description = "Role: INVENTORY_WORKER. One synchronous attempt for a shipment in TTN_FAILED.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "TTN generated")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not an INVENTORY_WORKER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No shipment for this order",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The shipment is not in TTN_FAILED, or changed meanwhile",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "424", description = "Inventory or the carrier rejected the attempt",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = TtnGenerationFailedProblem.class)))
    @PostMapping("/orders/{orderId}/ttn/retry")
    public RetryTtnResponse retryTtnGeneration(@PathVariable UUID orderId) {
        return new RetryTtnResponse(manualTtnRetryService.retry(orderId));
    }
}
