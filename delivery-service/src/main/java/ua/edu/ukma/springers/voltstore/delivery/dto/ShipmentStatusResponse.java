package ua.edu.ukma.springers.voltstore.delivery.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One entry of EP-DEL-01. {@code status} (and the other fields) are null when the order has no shipment yet, e.g.
 * because its {@code OrderPaid} has not been consumed.
 */
public record ShipmentStatusResponse(@Schema(requiredMode = REQUIRED) UUID orderId,
                                     ShipmentStatus status,
                                     String ttn,
                                     String failureReason) {

    public static ShipmentStatusResponse of(Shipment shipment) {
        return new ShipmentStatusResponse(shipment.getOrderId(), shipment.getStatus(), shipment.getTtn(),
                shipment.getFailureReason());
    }

    public static ShipmentStatusResponse notFound(UUID orderId) {
        return new ShipmentStatusResponse(orderId, null, null, null);
    }
}
