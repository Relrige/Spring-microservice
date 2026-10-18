package ua.edu.ukma.springers.voltstore.order.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.order.dto.FulfillmentQueueResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ShippedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.TtnNotGeneratedException;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;
import ua.edu.ukma.springers.voltstore.order.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.order.services.FulfillmentService;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EP-ORD-07 and EP-ORD-08 at the HTTP boundary, with the service mocked. */
@WebMvcTest(FulfillmentController.class)
@Import(SecurityConfig.class)
class FulfillmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FulfillmentService fulfillment;

    @Test
    void queue_isTheEnvelopeWithTheDeliveryFlag() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(fulfillment.queue()).thenReturn(new FulfillmentQueueResponse(true, List.of(
                new FulfillmentQueueResponse.Entry(orderId, OrderStatus.PENDING_SHIPPING,
                        Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-17T10:01:00Z"),
                        List.of(new FulfillmentQueueResponse.Item(UUID.randomUUID(), "Phone", 2)),
                        new FulfillmentQueueResponse.Delivery("Ivan Petrenko", "+380501234567", "Kyiv, branch 12",
                                "NOVA_POSHTA"),
                        DeliveryStatus.TTN_GENERATED, "20450012345678", null))));

        mockMvc.perform(get("/orders/fulfillment-queue").headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryDataAvailable").value(true))
                .andExpect(jsonPath("$.orders[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.orders[0].items[0].quantity").value(2))
                .andExpect(jsonPath("$.orders[0].deliveryDetails.carrier").value("NOVA_POSHTA"))
                .andExpect(jsonPath("$.orders[0].deliveryStatus").value("TTN_GENERATED"))
                .andExpect(jsonPath("$.orders[0].ttn").value("20450012345678"));
    }

    @Test
    void ship_is200_withTheTtn() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(fulfillment.ship(orderId))
                .thenReturn(new ShippedOrderResponse(orderId, OrderStatus.SHIPPED, "20450012345678"));

        ship(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.ttn").value("20450012345678"));
    }

    @Test
    void ship_withoutATtn_is409() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(fulfillment.ship(orderId)).thenThrow(new TtnNotGeneratedException(orderId));

        ship(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("TTN must be generated before dispatching order " + orderId));
    }

    @Test
    void ship_unknownOrder_is404_andDeliveryDown_is503() throws Exception {
        UUID missing = UUID.randomUUID();
        when(fulfillment.ship(missing)).thenThrow(new OrderNotFoundException(missing));
        UUID other = UUID.randomUUID();
        when(fulfillment.ship(other)).thenThrow(new DependencyUnavailableException("Delivery", "down", null));

        ship(missing).andExpect(status().isNotFound());
        ship(other).andExpect(status().isServiceUnavailable());
    }

    private org.springframework.test.web.servlet.ResultActions ship(UUID orderId) throws Exception {
        return mockMvc.perform(post("/orders/" + orderId + "/ship")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER")));
    }
}
