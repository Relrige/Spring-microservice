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
import org.mockito.ArgumentCaptor;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutConfig;
import ua.edu.ukma.springers.voltstore.order.dto.CancellationResponse;
import ua.edu.ukma.springers.voltstore.order.dto.CardDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderDetailsResponse;
import ua.edu.ukma.springers.voltstore.order.dto.OrderSummaryResponse;
import ua.edu.ukma.springers.voltstore.order.dto.PaidOrderResponse;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.exceptions.CancellationInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IdempotencyKeyMismatchException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderTotalTooLargeException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentWindowExpiredException;
import ua.edu.ukma.springers.voltstore.order.exceptions.RequestInProgressException;
import ua.edu.ukma.springers.voltstore.order.paging.InvalidQueryParameterException;
import ua.edu.ukma.springers.voltstore.order.paging.PageResponse;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.order.services.CheckoutCheckService;
import ua.edu.ukma.springers.voltstore.order.services.OrderPaymentService;
import ua.edu.ukma.springers.voltstore.order.services.OrderPlacementService;
import ua.edu.ukma.springers.voltstore.order.services.OrderCancellationService;
import ua.edu.ukma.springers.voltstore.order.services.OrderQueryService;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EP-ORD-01 to EP-ORD-06 at the HTTP boundary: validation, status codes and bodies, with the services mocked. */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, CheckoutConfig.class})
class OrderControllerTest {
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String PHONE = UUID.randomUUID().toString();
    private static final String CARD =
            "{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2099,\"cvv\":\"123\"}";
    private static final String DELIVERY = """
            {"name":"Ivan Petrenko","phone":"+380501234567","address":"Kyiv, branch 12","carrier":"NOVA_POSHTA"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutCheckService checkoutCheck;

    @MockitoBean
    private OrderPlacementService placement;

    @MockitoBean
    private OrderPaymentService payment;

    @MockitoBean
    private OrderQueryService queries;

    @MockitoBean
    private OrderCancellationService cancellation;

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
            // More than the cart allows per product
            "{\"items\":[{\"productId\":\"00000000-0000-0000-0000-000000000001\",\"quantity\":11}]}",
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
        when(placement.place(any(), any(), any())).thenReturn(new OrderPlacementService.Placement(
                new PlacedOrderResponse(orderId, OrderStatus.PENDING_PAYMENT, new BigDecimal("39999.00"), "UAH",
                        expiresAt), false));

        place(placeBody(DELIVERY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/" + orderId))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
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
        when(placement.place(any(), any(), any()))
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
        when(placement.place(any(), any(), any())).thenThrow(new IneligibleItemsException(List.of(
                new IneligibleItem(UUID.fromString(PHONE), IneligibleItem.Reason.NOT_ACTIVE))));

        place(placeBody(DELIVERY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.ineligibleItems[0].reason").value("NOT_ACTIVE"));
    }

    @Test
    void place_aTotalTooLargeToPay_is422_withTheLimit() throws Exception {
        when(placement.place(any(), any(), any()))
                .thenThrow(new OrderTotalTooLargeException(new BigDecimal("9999999999.99")));

        place(placeBody(DELIVERY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/order-total-too-large"))
                .andExpect(jsonPath("$.maxTotal").value(9999999999.99));
    }

    @Test
    void place_dependencyUnavailable_is503() throws Exception {
        when(placement.place(any(), any(), any())).thenThrow(new DependencyUnavailableException("Inventory", "down", null));

        place(placeBody(DELIVERY)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void place_withoutEmailFromTheGateway_is401() throws Exception {
        when(placement.place(any(), any(), any())).thenThrow(new MissingCustomerEmailException());

        mockMvc.perform(post("/orders").headers(TrustedHeaders.asUser(CUSTOMER_ID, "CUSTOMER", null))
                        .contentType(MediaType.APPLICATION_JSON).content(placeBody(DELIVERY)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    // ---- EP-ORD-02 with an Idempotency-Key ----

    @Test
    void place_passesTheIdempotencyKey_andNoneWithoutTheHeader() throws Exception {
        UUID key = UUID.randomUUID();
        when(placement.place(any(), any(), any())).thenReturn(new OrderPlacementService.Placement(
                new PlacedOrderResponse(UUID.randomUUID(), OrderStatus.PENDING_PAYMENT, BigDecimal.TEN, "UAH",
                        Instant.parse("2026-10-17T10:15:00Z")), false));

        placeWithKey(key.toString(), placeBody(DELIVERY)).andExpect(status().isCreated());
        place(placeBody(DELIVERY)).andExpect(status().isCreated());

        verify(placement).place(any(), any(), eq(key));
        verify(placement).place(any(), any(), isNull());
    }

    @Test
    void place_aKeyThatIsNotAUuid_is400_withoutCallingTheService() throws Exception {
        placeWithKey("not-a-uuid", placeBody(DELIVERY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.service").value("order-service"));
        verifyNoInteractions(placement);
    }

    @Test
    void place_aReplay_is201_withLocation_andIdempotentReplayed() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(placement.place(any(), any(), any())).thenReturn(new OrderPlacementService.Placement(
                new PlacedOrderResponse(orderId, OrderStatus.PAID, new BigDecimal("39999.00"), "UAH",
                        Instant.parse("2026-10-17T10:15:00Z")), true));

        placeWithKey(UUID.randomUUID().toString(), placeBody(DELIVERY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/" + orderId))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void place_aKeyUsedForAnotherRequest_is422_idempotencyKeyMismatch() throws Exception {
        when(placement.place(any(), any(), any())).thenThrow(new IdempotencyKeyMismatchException());

        placeWithKey(UUID.randomUUID().toString(), placeBody(DELIVERY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/idempotency-key-mismatch"))
                .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    void place_aKeyStillInProgress_is409_withRetryAfterOneSecond() throws Exception {
        when(placement.place(any(), any(), any())).thenThrow(new RequestInProgressException());

        placeWithKey(UUID.randomUUID().toString(), placeBody(DELIVERY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/request-in-progress"))
                .andExpect(jsonPath("$.detail").value("A request with this Idempotency-Key is still being processed"));
    }

    // ---- EP-ORD-03 ----

    @Test
    void pay_is200_withTheNewStatus() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(payment.pay(eq(orderId), any(), any())).thenReturn(new PaidOrderResponse(orderId, OrderStatus.PAID));

        pay(orderId, CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PAID"));

        ArgumentCaptor<CardDetailsRequest> card = ArgumentCaptor.forClass(CardDetailsRequest.class);
        verify(payment).pay(eq(orderId), any(), card.capture());
        assertThat(card.getValue().number()).isEqualTo("4242424242424242");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"number\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2099,\"cvv\":\"123\"}",
            "{\"number\":\"4242424242424242\",\"expiryMonth\":13,\"expiryYear\":2099,\"cvv\":\"123\"}",
            "{\"number\":\"4242424242424242\",\"expiryMonth\":1,\"expiryYear\":2020,\"cvv\":\"123\"}",
            "{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2099,\"cvv\":\"12\"}",
            "{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2099}"
    })
    void pay_invalidCard_is400_beforeAnyCall_andNeverEchoesTheCard(String card) throws Exception {
        String response = pay(UUID.randomUUID(), card)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("4242424242424242").doesNotContain("\"123\"");
        verifyNoInteractions(payment);
    }

    @Test
    void pay_withoutCardDetails_is400() throws Exception {
        mockMvc.perform(post("/orders/" + UUID.randomUUID() + "/pay").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("cardDetails")));
    }

    @Test
    void pay_declined_is402_withTheReason() throws Exception {
        when(payment.pay(any(), any(), any())).thenThrow(new PaymentDeclinedException("insufficient_funds"));

        pay(UUID.randomUUID(), CARD)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.reason").value("insufficient_funds"));
    }

    @Test
    void pay_windowExpired_is409_withItsOwnType() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(payment.pay(any(), any(), any())).thenThrow(new PaymentWindowExpiredException(orderId));

        pay(orderId, CARD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/payment-window-expired"));
    }

    @Test
    void pay_notAwaitingPayment_is409_withTheStatus() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(payment.pay(any(), any(), any()))
                .thenThrow(new InvalidOrderStateException(orderId, OrderStatus.CANCELLED, "be paid"));

        pay(orderId, CARD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.orderStatus").value("CANCELLED"));
    }

    @Test
    void pay_someoneElsesOrder_is404() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(payment.pay(any(), any(), any())).thenThrow(new OrderNotFoundException(orderId));

        pay(orderId, CARD).andExpect(status().isNotFound());
    }

    // ---- EP-ORD-04 / EP-ORD-05 ----

    @Test
    void history_defaultsToTheFirstPageOf20() throws Exception {
        when(queries.history(any(), eq(0), eq(20))).thenReturn(new PageResponse<>(List.of(new OrderSummaryResponse(
                UUID.randomUUID(), OrderStatus.PAID, new BigDecimal("100.00"), "UAH",
                Instant.parse("2026-10-17T10:00:00Z"))), 0, 20, 1));

        mockMvc.perform(get("/orders").headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.content[0].placedAt").value("2026-10-17T10:00:00Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void history_invalidPaging_is400_namingTheParameter() throws Exception {
        when(queries.history(any(), eq(0), eq(500)))
                .thenThrow(new InvalidQueryParameterException("size", "size must be between 1 and 100"));

        mockMvc.perform(get("/orders?size=500").headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size[0]").value("size must be between 1 and 100"));
        mockMvc.perform(get("/orders?page=abc").headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void details_is200_withItemsDeliveryAndShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(queries.details(eq(orderId), any())).thenReturn(new OrderDetailsResponse(orderId, OrderStatus.PAID,
                List.of(new OrderDetailsResponse.Item(UUID.fromString(PHONE), "Phone", new BigDecimal("100.00"), 2)),
                new OrderDetailsResponse.Delivery("Ivan Petrenko", "+380501234567", "Kyiv, branch 12",
                        "NOVA_POSHTA"),
                new BigDecimal("200.00"), "UAH", Instant.parse("2026-10-17T10:00:00Z"),
                Instant.parse("2026-10-17T10:01:00Z"), Instant.parse("2026-10-17T10:15:00Z"), "20450012345678",
                Shipments.DeliveryStatus.TTN_GENERATED));

        mockMvc.perform(get("/orders/" + orderId).headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productTitle").value("Phone"))
                .andExpect(jsonPath("$.items[0].unitPriceAtOrder").value(100.00))
                .andExpect(jsonPath("$.deliveryDetails.address").value("Kyiv, branch 12"))
                .andExpect(jsonPath("$.paidAt").value("2026-10-17T10:01:00Z"))
                .andExpect(jsonPath("$.ttn").value("20450012345678"))
                .andExpect(jsonPath("$.deliveryStatus").value("TTN_GENERATED"));
    }

    @Test
    void details_ofAMissingOrForeignOrder_is404() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(queries.details(eq(orderId), any())).thenThrow(new OrderNotFoundException(orderId));

        mockMvc.perform(get("/orders/" + orderId).headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isNotFound());
    }

    // ---- EP-ORD-06 ----

    @Test
    void cancel_is202_withTheOutcome() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(cancellation.cancel(eq(orderId), any()))
                .thenReturn(new CancellationResponse(orderId, OrderStatus.CANCELLATION_REQUESTED));

        mockMvc.perform(delete("/orders/" + orderId).headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLATION_REQUESTED"));
    }

    @Test
    void cancel_inProgress_is409() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(cancellation.cancel(eq(orderId), any())).thenThrow(new CancellationInProgressException(orderId));

        mockMvc.perform(delete("/orders/" + orderId).headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cancellation of order " + orderId + " is already in progress"))
                .andExpect(jsonPath("$.orderStatus").value("CANCELLATION_REQUESTED"));
    }

    @Test
    void cancel_aShippedOrder_is409() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(cancellation.cancel(eq(orderId), any()))
                .thenThrow(new InvalidOrderStateException(orderId, OrderStatus.SHIPPED, "be cancelled"));

        mockMvc.perform(delete("/orders/" + orderId).headers(TrustedHeaders.asCustomer(CUSTOMER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.orderStatus").value("SHIPPED"));
    }

    private ResultActions pay(UUID orderId, String card) throws Exception {
        return mockMvc.perform(post("/orders/" + orderId + "/pay").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardDetails\":" + card + "}"));
    }

    private ResultActions validate(String body) throws Exception {
        return mockMvc.perform(post("/orders/validate").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions place(String body) throws Exception {
        return mockMvc.perform(post("/orders").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions placeWithKey(String key, String body) throws Exception {
        return mockMvc.perform(post("/orders").headers(TrustedHeaders.asCustomer(CUSTOMER_ID))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String placeBody(String delivery) {
        return "{\"items\":[" + item(PHONE, 2) + "],\"deliveryDetails\":" + delivery + "}";
    }

    private static String item(String productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }
}
