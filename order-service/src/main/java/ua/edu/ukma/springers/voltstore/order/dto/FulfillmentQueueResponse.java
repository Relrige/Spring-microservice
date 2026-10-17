package ua.edu.ukma.springers.voltstore.order.dto;

import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.ShipmentInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * EP-ORD-07: the paid orders waiting for dispatch, oldest payment first, with their shipment from Delivery.
 *
 * @param deliveryDataAvailable {@code false} when Delivery could not be asked: the orders are shown without
 *                              {@code deliveryStatus}, {@code ttn} and {@code failureReason}, which then do not mean
 *                              "no shipment"
 */
public record FulfillmentQueueResponse(boolean deliveryDataAvailable, List<Entry> orders) {

    public record Entry(UUID orderId, OrderStatus status, Instant placedAt, Instant paidAt, List<Item> items,
                        Delivery deliveryDetails, DeliveryStatus deliveryStatus, String ttn, String failureReason) {

        /** @param shipment {@code null} when Delivery has no shipment for the order or was not asked */
        public static Entry of(OrderEntity order, ShipmentInfo shipment) {
            return new Entry(order.getId(), order.getStatus(), order.getPlacedAt(), order.getPaidAt(),
                    order.getItems().stream().map(Item::of).toList(), Delivery.of(order.getDeliveryDetails()),
                    shipment == null ? null : shipment.status(), shipment == null ? null : shipment.ttn(),
                    shipment == null ? null : shipment.failureReason());
        }
    }

    /** What the worker picks from the shelf. */
    public record Item(UUID productId, String productTitle, int quantity) {

        static Item of(OrderItemEntity item) {
            return new Item(item.getProductId(), item.getProductTitle(), item.getQuantity());
        }
    }

    public record Delivery(String name, String phone, String address, String carrier) {

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
