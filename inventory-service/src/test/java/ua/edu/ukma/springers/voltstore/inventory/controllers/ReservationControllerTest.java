package ua.edu.ukma.springers.voltstore.inventory.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@Import(SecurityConfig.class)
class ReservationControllerTest {
    private static final String URL = "/inventory/reservations";
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID PRODUCT_A = UUID.randomUUID();
    private static final UUID PRODUCT_B = UUID.randomUUID();
    private static final HttpHeaders SERVICE = TrustedHeaders.asInternalService();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    void reserve_validRequest_returns204AndPassesQuantitiesPerProduct() throws Exception {
        reserve(SERVICE, body(ORDER_ID, item(PRODUCT_A, 2), item(PRODUCT_B, 1)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(reservationService).reserve(ORDER_ID, Map.of(PRODUCT_A, 2, PRODUCT_B, 1));
    }

    @Test
    void reserve_insufficientStock_returns422WithAllShortItems() throws Exception {
        doThrow(new InsufficientStockForReservationException(List.of(
                new InsufficientItem(PRODUCT_A, 2, 1), new InsufficientItem(PRODUCT_B, 1, 0))))
                .when(reservationService).reserve(any(), anyMap());

        reserve(SERVICE, body(ORDER_ID, item(PRODUCT_A, 2), item(PRODUCT_B, 1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/insufficient-stock"))
                .andExpect(jsonPath("$.insufficientItems.length()").value(2))
                .andExpect(jsonPath("$.insufficientItems[0].productId").value(PRODUCT_A.toString()))
                .andExpect(jsonPath("$.insufficientItems[0].requested").value(2))
                .andExpect(jsonPath("$.insufficientItems[0].available").value(1))
                .andExpect(jsonPath("$.insufficientItems[1].productId").value(PRODUCT_B.toString()))
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void reserve_orderAlreadyReserved_returns409() throws Exception {
        doThrow(new ReservationAlreadyExistsException(ORDER_ID)).when(reservationService).reserve(any(), anyMap());

        reserve(SERVICE, body(ORDER_ID, item(PRODUCT_A, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/conflict"));
    }

    @Test
    void reserve_missingOrderId_returns400() throws Exception {
        reserve(SERVICE, "{\"items\":[" + item(PRODUCT_A, 1) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderId").isNotEmpty());
        verifyNotCalled();
    }

    @Test
    void reserve_nonUuidOrderId_returns400() throws Exception {
        reserve(SERVICE, "{\"orderId\":\"abc\",\"items\":[" + item(PRODUCT_A, 1) + "]}")
                .andExpect(status().isBadRequest());
        verifyNotCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"items\":[]", "\"items\":null", ""})
    void reserve_emptyOrMissingItems_returns400(String items) throws Exception {
        String body = "{\"orderId\":\"" + ORDER_ID + "\"" + (items.isEmpty() ? "" : "," + items) + "}";

        reserve(SERVICE, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").isNotEmpty());
        verifyNotCalled();
    }

    @Test
    void reserve_tooManyItems_returns400() throws Exception {
        String[] items = IntStream.range(0, 101).mapToObj(i -> item(UUID.randomUUID(), 1)).toArray(String[]::new);

        reserve(SERVICE, body(ORDER_ID, items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items").isNotEmpty());
        verifyNotCalled();
    }

    @Test
    void reserve_maxItems_isAccepted() throws Exception {
        String[] items = IntStream.range(0, 100).mapToObj(i -> item(UUID.randomUUID(), 1)).toArray(String[]::new);

        reserve(SERVICE, body(ORDER_ID, items)).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void reserve_quantityBelowOne_returns400(int quantity) throws Exception {
        reserve(SERVICE, body(ORDER_ID, item(PRODUCT_A, 1), item(PRODUCT_B, quantity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[1].quantity']").isNotEmpty());
        verifyNotCalled();
    }

    @Test
    void reserve_missingProductIdOrQuantity_returns400() throws Exception {
        reserve(SERVICE, body(ORDER_ID, "{\"quantity\":1}", "{\"productId\":\"" + PRODUCT_B + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[0].productId']").isNotEmpty())
                .andExpect(jsonPath("$.errors['items[1].quantity']").isNotEmpty());
        verifyNotCalled();
    }

    @Test
    void reserve_nullItem_returns400() throws Exception {
        reserve(SERVICE, body(ORDER_ID, "null"))
                .andExpect(status().isBadRequest());
        verifyNotCalled();
    }

    @Test
    void reserve_nonUuidProductId_returns400() throws Exception {
        reserve(SERVICE, body(ORDER_ID, "{\"productId\":\"abc\",\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        verifyNotCalled();
    }

    @Test
    void reserve_sameProductTwice_returns400() throws Exception {
        reserve(SERVICE, body(ORDER_ID, item(PRODUCT_A, 1), item(PRODUCT_B, 1), item(PRODUCT_A, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.items[0]").value("Each product may appear only once"));
        verifyNotCalled();
    }

    @Test
    void reserve_anonymous_returns401() throws Exception {
        reserve(new HttpHeaders(), body(ORDER_ID, item(PRODUCT_A, 1)))
                .andExpect(status().isUnauthorized());
        verifyNotCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "INVENTORY_WORKER", "CUSTOMER"})
    void reserve_anyUserRole_returns403(String role) throws Exception {
        reserve(TrustedHeaders.asUser(role), body(ORDER_ID, item(PRODUCT_A, 1)))
                .andExpect(status().isForbidden());
        verifyNotCalled();
    }

    @Test
    void release_returns204WithEmptyBody() throws Exception {
        mockMvc.perform(post(URL + "/" + ORDER_ID + "/release").headers(SERVICE))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(reservationService).release(ORDER_ID);
    }

    @Test
    void release_nonUuidOrderId_returns400() throws Exception {
        mockMvc.perform(post(URL + "/not-a-uuid/release").headers(SERVICE))
                .andExpect(status().isBadRequest());

        verify(reservationService, never()).release(any());
    }

    @Test
    void release_anonymous_returns401() throws Exception {
        mockMvc.perform(post(URL + "/" + ORDER_ID + "/release"))
                .andExpect(status().isUnauthorized());

        verify(reservationService, never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "INVENTORY_WORKER", "CUSTOMER"})
    void release_anyUserRole_returns403(String role) throws Exception {
        mockMvc.perform(post(URL + "/" + ORDER_ID + "/release").headers(TrustedHeaders.asUser(role)))
                .andExpect(status().isForbidden());

        verify(reservationService, never()).release(any());
    }

    private ResultActions reserve(HttpHeaders headers, String body) throws Exception {
        return mockMvc.perform(post(URL).headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void verifyNotCalled() {
        verify(reservationService, never()).reserve(any(), anyMap());
    }

    private static String body(UUID orderId, String... items) {
        return "{\"orderId\":\"" + orderId + "\",\"items\":["
                + java.util.Arrays.stream(items).collect(Collectors.joining(",")) + "]}";
    }

    private static String item(UUID productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
    }
}
