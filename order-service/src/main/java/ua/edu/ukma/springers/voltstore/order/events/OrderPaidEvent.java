package ua.edu.ukma.springers.voltstore.order.events;

import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The order was paid (EP-ORD-03). A fat event: Delivery creates the shipment from it without calling Order back
 * (event-carried state transfer), Customer clears the cart, Notification sends the receipt. It therefore carries
 * the recipient's personal data; never log it.
 */
public record OrderPaidEvent(UUID eventId, UUID orderId, UUID customerId, String customerEmail,
                             Delivery deliveryDetails, List<Item> items, BigDecimal totalAmount, String currency,
                             Instant paidAt, Instant timestamp) implements OrderEvent {

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

    public record Item(UUID productId, int quantity) {

        static Item of(OrderItemEntity item) {
            return new Item(item.getProductId(), item.getQuantity());
        }
    }

    public static OrderPaidEvent of(OrderEntity order, Instant now) {
        return new OrderPaidEvent(UUID.randomUUID(), order.getId(), order.getCustomerId(), order.getCustomerEmail(),
                Delivery.of(order.getDeliveryDetails()), order.getItems().stream().map(Item::of).toList(),
                order.getTotalAmount(), order.getCurrency(), Objects.requireNonNull(order.getPaidAt()), now);
    }

    @Override
    public String eventType() {
        return "OrderPaid";
    }
}
