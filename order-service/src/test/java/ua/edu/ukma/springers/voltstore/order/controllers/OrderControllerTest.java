package ua.edu.ukma.springers.voltstore.order.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutConfig;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.order.services.CheckoutCheckService;
import ua.edu.ukma.springers.voltstore.order.services.OrderPlacementService;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EP-ORD-01 and EP-ORD-02 at the HTTP boundary: validation, status codes and bodies, with the services mocked. */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, CheckoutConfig.class})
class OrderControllerTest {
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String PHONE = UUID.randomUUID().toString();
    private static final String DELIVERY = """
            {"name":"Ivan Petrenko","phone":"+380501234567","address":"Kyiv, branch 12","carrier":"NOVA_POSHTA"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutCheckService checkoutCheck;

    @MockitoBean
    private OrderPlacementService placement;

    // ---- EP-ORD-01 ----

    @Test
    void validate_allEligible_is204() throws Exception {
        validate("{\"items\":[" + item(PHONE, 2) + "]}").andExpect(status().isNoContent());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"quantity\":1}]}",
            "{\"items\":[{\"productId\":\"not-a-uuid\",\"quantity\":1}]}",
            "{\"items\":[{\"productId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":0}]}",
            "{\"items\":[{\"productId\":\"00000000-0000-0000-0000-000000000001\"}]}",
            "{\"items\":[{\"productId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":1},"
                    + "{\"productId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":2}]}"
    })
    void validate_invalidItems_is400_withoutCallingCatalog(String body) throws Exception {
        validate(body).andExpect(status().isBadRequest());
        verifyNoInteractions(checkoutCheck);
    }

    @Test
    void validate_moreThan100Items_is400() throws Exception {
        String items = IntStream.range(0, 101).mapToObj(i -> item(UUID.randomUUID().toString(), 1))
                .collect(Collectors.joining(","));

        validate("{\"items\":[" + items + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("items")));
    }

    @Test
    void validate_exactly100Items_isAccepted() throws Exception {
        String items = IntStream.range(0, 100).mapToObj(i -> item(UUID.randomUUID().toString(), 1))
                .collect(Collectors.joining(","));

        validate("{\"items\":[" + items + "]}").andExpect(status().isNoContent());
    }

    @Test
    void validate_ineligibleItems_is422_listingThem() throws Exception {
        UUID inactive = UUID.randomUUID();
        UUID soldOut = UUID.randomUUID();
        doThrow(new IneligibleItemsException(List.of(
                new IneligibleItem(inactive, IneligibleItem.Reason.NOT_ACTIVE),
                new IneligibleItem(soldOut, IneligibleItem.Reason.INSUFFICIENT_STOCK))))
                .when(checkoutCheck).check(anyList());

        validate("{\"items\":[" + item(inactive.toString(), 1) + "," + item(soldOut.toString(), 1) + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/ineligible-items"))
                .andExpect(jsonPath("$.ineligibleItems.length()").value(2))
                .andExpect(jsonPath("$.ineligibleItems[0].productId").value(inactive.toString()))
                .andExpect(jsonPath("$.ineligibleItems[0].reason").value("NOT_ACTIVE"))
                .andExpect(jsonPath("$.ineligibleItems[1].reason").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void validate_catalogUnavailable_is503() throws Exception {
        doThrow(new DependencyUnavailableException("Catalog", "down", null)).when(checkoutCheck).check(anyList());

        validate("{\"items\":[" + item(PHONE, 1) + "]}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"));
    }

    // ---- EP-ORD-02 ----

    @Test
    void place_is201_withLocationAndBody() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-10-17T10:15:00Z");
        when(placement.place(any(), any())).thenReturn(new PlacedOrderResponse(orderId, OrderStatus.PENDING_PAYMENT,
                new BigDecimal("39999.00"), "UAH", expiresAt));

        place(placeBody(DELIVERY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/" + orderId))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(39999.00))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.reservationExpiresAt").value("2026-10-17T10:15:00Z"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"name\":\" \",\"phone\":\"+380501234567\",\"address\":\"Kyiv\",\"carrier\":\"NOVA_POSHTA\"}",
            "{\"name\":\"Ivan\",\"phone\":\"0501234567\",\"address\":\"Kyiv\",\"carrier\":\"NOVA_POSHTA\"}",
            "{\"name\":\"Ivan\",\"phone\":\"+38 050 123\",\"address\":\"Kyiv\",\"carrier\":\"NOVA_POSHTA\"}",
            "{\"name\":\"Ivan\",\"phone\":\"+380501234567\",\"address\":\"\",\"carrier\":\"NOVA_POSHTA\"}",
            "{\"name\":\"Ivan\",\"phone\":\"+380501234567\",\"address\":\"Kyiv\",\"carrier\":\"DHL\"}",
            "{\"name\":\"Ivan\",\"phone\":\"+380501234567\",\"address\":\"Kyiv\"}"
    })
    void place_invalidDeliveryDetails_is400(String delivery) throws Exception {
        place(placeBody(delivery)).andExpect(status().isBadRequest());
        verifyNoInteractions(placement);
    }

    @Test
    void place_withoutDeliveryDetails_is400() throws Exception {
        place("{\"items\":[" + item(PHONE, 1) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("deliveryDetails")));
    }

    @Test
    void place_aDuplicateProduct_is400() throws Exception {
        place("{\"items\":[" + item(PHONE, 1) + "," + item(PHONE, 2) + "],\"deliveryDetails\":" + DELIVERY + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items[0]").value("Each product may appear only once"));
    }

    @Test
    void place_validationErrors_neverEchoTheRejectedValue() throws Exception {
        place(placeBody("{\"name\":\"Ivan\",\"phone\":\"secret-phone\",\"address\":\"Kyiv\","
                + "\"carrier\":\"NOVA_POSHTA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['deliveryDetails.phone'][0]")
                        .value("Phone must be in international format, e.g. +380501234567"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-phone"));
    }

    @Test
    void place_insufficientStock_is422_withInventorysList() throws Exception {
        UUID productId = UUID.fromString(PHONE);
        when(placement.place(any(), any()))
                .thenThrow(new InsufficientStockException(List.of(new InsufficientItem(productId, 5, 2))));

        place(placeBody(DELIVERY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/insufficient-stock"))
                .andExpect(jsonPath("$.insufficientItems[0].productId").value(PHONE))
                .andExpect(jsonPath("$.insufficientItems[0].requested").value(5))
                .andExpect(jsonPath("$.insufficientItems[0].available").value(2));
    }

    @Test
    void place_ineligibleItems_is422() throws Exception {
        when(placement.place(any(), any())).thenThrow(new IneligibleItemsException(List.of(
                new IneligibleItem(UUID.fromString(PHONE), IneligibleItem.Reason.NOT_ACTIVE))));

        place(placeBody(DELIVERY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.ineligibleItems[0].reason").value("NOT_ACTIVE"));
    }

    @Test
    void place_dependencyUnavailable_is503() throws Exception {
        when(placement.place(any(), any())).thenThrow(new DependencyUnavailableException("Inventory", "down", null));

        place(placeBody(DELIVERY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void place_withoutEmailFromTheGateway_is401() throws Exception {
        when(placement.place(any(), any())).thenThrow(new MissingCustomerEmailException());

        mockMvc.perform(post("/orders").headers(TrustedHeaders.asUser(CUSTOMER_ID, "CUSTOMER", null))
                        .contentType(MediaType.APPLICATION_JSON).content(placeBody(DELIVERY)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    private ResultActions validate(String body) throws Exception {
        return mockMvc.perform(post("/orders/validate").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions place(String body) throws Exception {
        return mockMvc.perform(post("/orders").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String placeBody(String delivery) {
        return "{\"items\":[" + item(PHONE, 2) + "],\"deliveryDetails\":" + delivery + "}";
    }

    private static String item(String productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }
}
