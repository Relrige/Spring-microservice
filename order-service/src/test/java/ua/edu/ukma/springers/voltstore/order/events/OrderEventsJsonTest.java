package ua.edu.ukma.springers.voltstore.order.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format of the four order events, checked against the Event Catalogue and against what the consumers
 * require (Delivery rejects an {@code OrderPaid} without every delivery field, items, total or currency).
 */
class OrderEventsJsonTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final UUID PHONE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void orderPlaced_hasTheCataloguesFields() {
        JsonNode json = json(OrderPlacedEvent.of(order(), NOW));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("eventId", "orderId", "customerId", "customerEmail",
                "totalAmount", "currency", "reservationExpiresAt", "timestamp");
        assertThat(json.get("totalAmount").decimalValue()).isEqualByComparingTo("39999.00");
        assertThat(json.get("reservationExpiresAt").asString()).isEqualTo("2026-10-16T12:15:00Z");
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-10-16T12:00:00Z");
    }

    @Test
    void orderPaid_carriesEverythingDeliveryNeedsToCreateTheShipment() {
        OrderEntity order = order();
        order.markPaid(NOW.plusSeconds(30));

        JsonNode json = json(OrderPaidEvent.of(order, NOW.plusSeconds(30)));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("eventId", "orderId", "customerId", "customerEmail",
                "deliveryDetails", "items", "totalAmount", "currency", "paidAt", "timestamp");
        assertThat(fieldNames(json.get("deliveryDetails"))).containsExactlyInAnyOrder("name", "phone", "address",
                "carrier");
        assertThat(json.get("deliveryDetails").get("carrier").asString()).isEqualTo("NOVA_POSHTA");
        assertThat(json.get("items")).hasSize(1);
        assertThat(json.get("items").get(0).get("productId").asString()).isEqualTo(PHONE.toString());
        assertThat(json.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(json.get("customerId").asString()).as("never null: registered customers only")
                .isEqualTo(order.getCustomerId().toString());
        assertThat(json.get("currency").asString()).isEqualTo("UAH");
    }

    @Test
    void orderShipped_carriesTheTtnAndTheEmail() {
        JsonNode json = json(OrderShippedEvent.of(order(), "20450012345678", NOW));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("eventId", "orderId", "ttn", "customerEmail",
                "timestamp");
        assertThat(json.get("ttn").asString()).isEqualTo("20450012345678");
    }

    @Test
    void orderCancelled_carriesTheReason() {
        JsonNode json = json(OrderCancelledEvent.of(order(), OrderCancelledEvent.Reason.EXPIRED, NOW));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder("eventId", "orderId", "customerId", "customerEmail",
                "reason", "timestamp");
        assertThat(json.get("reason").asString()).isEqualTo("EXPIRED");
    }

    @Test
    void theEventTypeIsAHeader_notAPayloadField() {
        OrderEvent event = OrderPlacedEvent.of(order(), NOW);

        assertThat(event.eventType()).isEqualTo("OrderPlaced");
        assertThat(json(event).has("eventType")).isFalse();
    }

    private static JsonNode json(OrderEvent event) {
        return JSON.readTree(JSON.writeValueAsString(event));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static OrderEntity order() {
        return OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(PHONE, "Phone", new BigDecimal("19999.50"), 2)),
                "UAH", NOW, Duration.ofMinutes(15));
    }
}
