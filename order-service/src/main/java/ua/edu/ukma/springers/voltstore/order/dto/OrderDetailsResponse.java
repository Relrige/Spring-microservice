package ua.edu.ukma.springers.voltstore.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * EP-ORD-05. {@code ttn} and {@code deliveryStatus} come from Delivery and are {@code null} when there is no
 * shipment yet or Delivery is unavailable.
 */
public record OrderDetailsResponse(@Schema(requiredMode = REQUIRED) UUID orderId,
                                   @Schema(requiredMode = REQUIRED) OrderStatus status,
                                   @Schema(requiredMode = REQUIRED) List<Item> items,
                                   @Schema(requiredMode = REQUIRED) Delivery deliveryDetails,
                                   @Schema(requiredMode = REQUIRED) BigDecimal totalAmount,
                                   @Schema(requiredMode = REQUIRED) String currency,
                                   @Schema(requiredMode = REQUIRED) Instant placedAt,
                                   Instant paidAt,
                                   @Schema(requiredMode = REQUIRED) Instant reservationExpiresAt,
                                   String ttn,
                                   DeliveryStatus deliveryStatus) {

    @Schema(name = "OrderDetailsItem")
    public record Item(@Schema(requiredMode = REQUIRED) UUID productId,
                       @Schema(requiredMode = REQUIRED) String productTitle,
                       @Schema(requiredMode = REQUIRED) BigDecimal unitPriceAtOrder,
                       @Schema(requiredMode = REQUIRED) int quantity) {

        static Item of(OrderItemEntity item) {
            return new Item(item.getProductId(), item.getProductTitle(), item.getUnitPriceAtOrder(),
                    item.getQuantity());
        }
    }

    @Schema(name = "OrderDetailsDelivery")
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

    public static OrderDetailsResponse of(OrderEntity order, String ttn, DeliveryStatus deliveryStatus) {
        return new OrderDetailsResponse(order.getId(), order.getStatus(),
                order.getItems().stream().map(Item::of).toList(), Delivery.of(order.getDeliveryDetails()),
                order.getTotalAmount(), order.getCurrency(), order.getPlacedAt(), order.getPaidAt(),
                order.getReservationExpiresAt(), ttn, deliveryStatus);
    }
}
