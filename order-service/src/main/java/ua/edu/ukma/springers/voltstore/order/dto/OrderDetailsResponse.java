package ua.edu.ukma.springers.voltstore.order.dto;

import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * EP-ORD-05. {@code ttn} and {@code deliveryStatus} come from Delivery and are {@code null} when there is no
 * shipment yet or Delivery is unavailable.
 */
public record OrderDetailsResponse(UUID orderId, OrderStatus status, List<Item> items, Delivery deliveryDetails,
                                   BigDecimal totalAmount, String currency, Instant placedAt, Instant paidAt,
                                   Instant reservationExpiresAt, String ttn, DeliveryStatus deliveryStatus) {

    public record Item(UUID productId, String productTitle, BigDecimal unitPriceAtOrder, int quantity) {

        static Item of(OrderItemEntity item) {
            return new Item(item.getProductId(), item.getProductTitle(), item.getUnitPriceAtOrder(),
                    item.getQuantity());
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

    public static OrderDetailsResponse of(OrderEntity order, String ttn, DeliveryStatus deliveryStatus) {
        return new OrderDetailsResponse(order.getId(), order.getStatus(),
                order.getItems().stream().map(Item::of).toList(), Delivery.of(order.getDeliveryDetails()),
                order.getTotalAmount(), order.getCurrency(), order.getPlacedAt(), order.getPaidAt(),
                order.getReservationExpiresAt(), ttn, deliveryStatus);
    }
}
