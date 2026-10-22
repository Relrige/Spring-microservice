package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.ShipmentInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * EP-ORD-07: the paid orders waiting for dispatch, oldest payment first, with their shipment from Delivery.
 *
 * @param deliveryDataAvailable {@code false} when Delivery could not be asked: the orders are shown without
 *                              {@code deliveryStatus}, {@code ttn} and {@code failureReason}, which then do not mean
 *                              "no shipment"
 */
public record FulfillmentQueueResponse(@Schema(requiredMode = REQUIRED) boolean deliveryDataAvailable,
                                       @Schema(requiredMode = REQUIRED) List<Entry> orders) {

    @Schema(name = "FulfillmentQueueEntry")
    public record Entry(@Schema(requiredMode = REQUIRED) UUID orderId,
                        @Schema(requiredMode = REQUIRED) OrderStatus status,
                        @Schema(requiredMode = REQUIRED) Instant placedAt,
                        @Schema(requiredMode = REQUIRED) Instant paidAt,
                        @Schema(requiredMode = REQUIRED) List<Item> items,
                        @Schema(requiredMode = REQUIRED) Delivery deliveryDetails,
                        DeliveryStatus deliveryStatus,
                        String ttn,
                        String failureReason) {

        /** @param shipment {@code null} when Delivery has no shipment for the order or was not asked */
        public static Entry of(OrderEntity order, ShipmentInfo shipment) {
            return new Entry(order.getId(), order.getStatus(), order.getPlacedAt(), order.getPaidAt(),
                    order.getItems().stream().map(Item::of).toList(), Delivery.of(order.getDeliveryDetails()),
                    shipment == null ? null : shipment.status(), shipment == null ? null : shipment.ttn(),
                    shipment == null ? null : shipment.failureReason());
        }
    }

    /** What the worker picks from the shelf. */
    @Schema(name = "FulfillmentQueueItem")
    public record Item(@Schema(requiredMode = REQUIRED) UUID productId,
                       @Schema(requiredMode = REQUIRED) String productTitle,
                       @Schema(requiredMode = REQUIRED) int quantity) {

        static Item of(OrderItemEntity item) {
            return new Item(item.getProductId(), item.getProductTitle(), item.getQuantity());
        }
    }

    @Schema(name = "FulfillmentQueueDelivery")
    public record Delivery(@Schema(requiredMode = REQUIRED) String name,
                           @Schema(requiredMode = REQUIRED) String phone,
                           @Schema(requiredMode = REQUIRED) String address,
                           @Schema(requiredMode = REQUIRED) String carrier) {

        static Delivery of(DeliveryDetails details) {
            return new Delivery(details.getName(), details.getPhone(), details.getAddress(), details.getCarrier());
        }

        // Personal data
        @Override
        public String toString() {
            return "Delivery[carrier=" + carrier + "]";
        }
    }
}
