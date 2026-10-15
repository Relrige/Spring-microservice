package ua.edu.ukma.springers.voltstore.delivery.controllers;

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
import ua.edu.ukma.springers.voltstore.delivery.services.ManualTtnRetryService;
import ua.edu.ukma.springers.voltstore.delivery.services.ShipmentService;

import java.util.List;
import java.util.UUID;

// Who may call which route is declared in SecurityConfig
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
    @GetMapping("/shipments")
    public List<ShipmentStatusResponse> getShipmentStatuses(
            @RequestParam(required = false) List<UUID> orderIds) {
        return shipmentService.getStatuses(orderIds);
    }

    /**
     * EP-DEL-02 (internal, Order's cancellation saga): {@code 204} for every outcome except a parcel already handed
     * to the carrier ({@code 409}).
     */
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
    @PostMapping("/orders/{orderId}/ttn/retry")
    public RetryTtnResponse retryTtnGeneration(@PathVariable UUID orderId) {
        return new RetryTtnResponse(manualTtnRetryService.retry(orderId));
    }
}
