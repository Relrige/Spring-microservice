package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.dto.DeliveryDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The request hash of D9: the same request in another form gives the same hash, any changed field another one. */
class RequestHashTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PHONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CASE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final DeliveryDetailsRequest DELIVERY =
            new DeliveryDetailsRequest("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA");

    private final IdempotencyKeys keys = new IdempotencyKeys(mock(), TransactionOperations.withoutTransaction(), JSON,
            Clock.systemUTC());

    @Test
    void isSha256Hex() {
        assertThat(keys.hash(request(DELIVERY, item(PHONE, 1)))).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void itemsInAnotherOrder_giveTheSameHash() {
        assertThat(keys.hash(request(DELIVERY, item(CASE, 1), item(PHONE, 2))))
                .isEqualTo(keys.hash(request(DELIVERY, item(PHONE, 2), item(CASE, 1))));
    }

    @Test
    void otherJsonFormatting_givesTheSameHash() {
        // Parsed the way the controller parses the body: whitespace and field order do not reach the hash
        String compact = """
                {"items":[{"productId":"%s","quantity":2}],"deliveryDetails":{"name":"Ivan Petrenko",\
                "phone":"+380501234567","address":"Kyiv, branch 12","carrier":"NOVA_POSHTA"}}""".formatted(PHONE);
        String spaced = """
                {
                  "deliveryDetails": { "carrier": "NOVA_POSHTA", "address": "Kyiv, branch 12",
                                       "phone": "+380501234567", "name": "Ivan Petrenko" },
                  "items": [ { "quantity": 2, "productId": "%s" } ]
                }""".formatted(PHONE);

        assertThat(keys.hash(JSON.readValue(spaced, PlaceOrderRequest.class)))
                .isEqualTo(keys.hash(JSON.readValue(compact, PlaceOrderRequest.class)));
    }

    @Test
    void aChangedQuantityOrProduct_givesAnotherHash() {
        String hash = keys.hash(request(DELIVERY, item(PHONE, 1)));

        assertThat(keys.hash(request(DELIVERY, item(PHONE, 2)))).isNotEqualTo(hash);
        assertThat(keys.hash(request(DELIVERY, item(CASE, 1)))).isNotEqualTo(hash);
        assertThat(keys.hash(request(DELIVERY, item(PHONE, 1), item(CASE, 1)))).isNotEqualTo(hash);
    }

    @Test
    void anyChangedDeliveryField_givesAnotherHash() {
        String hash = keys.hash(request(DELIVERY, item(PHONE, 1)));

        assertThat(List.of(
                new DeliveryDetailsRequest("Ivan Petrenko2", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                new DeliveryDetailsRequest("Ivan Petrenko", "+380501234568", "Kyiv, branch 12", "NOVA_POSHTA"),
                new DeliveryDetailsRequest("Ivan Petrenko", "+380501234567", "Kyiv, branch 13", "NOVA_POSHTA"),
                new DeliveryDetailsRequest("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "UKRPOSHTA")))
                .allSatisfy(changed -> assertThat(keys.hash(request(changed, item(PHONE, 1)))).isNotEqualTo(hash));
    }

    private static OrderItemRequest item(UUID productId, int quantity) {
        return new OrderItemRequest(productId, quantity);
    }

    private static PlaceOrderRequest request(DeliveryDetailsRequest delivery, OrderItemRequest... items) {
        return new PlaceOrderRequest(List.of(items), delivery);
    }
}
