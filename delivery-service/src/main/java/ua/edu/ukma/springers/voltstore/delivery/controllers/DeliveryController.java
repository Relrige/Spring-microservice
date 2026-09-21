package ua.edu.ukma.springers.voltstore.delivery.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.delivery.domain.entity.Consignment;
import ua.edu.ukma.springers.voltstore.delivery.services.DeliveryService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/delivery")
@RequiredArgsConstructor
public class DeliveryController {
    private final DeliveryService deliveryService;

    @PostMapping
    public ResponseEntity<Consignment> createConsignment(@RequestBody Consignment consignment) {
        return ResponseEntity.ok(deliveryService.createConsignment(consignment));
    }

    @PatchMapping("/{orderId}/waybill")
    public ResponseEntity<Consignment> updateWaybill(@PathVariable UUID orderId, @RequestParam String waybillNumber) {
        return ResponseEntity.ok(deliveryService.updateWaybill(orderId, waybillNumber));
    }

    @GetMapping("/track/{waybillNumber}")
    public ResponseEntity<Consignment> trackParcel(@PathVariable String waybillNumber) {
        return ResponseEntity.ok(deliveryService.getTrackingInfo(waybillNumber));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelDelivery(@PathVariable UUID orderId) {
        deliveryService.cancelDelivery(orderId);
        return ResponseEntity.ok().build();
    }
}