package ua.edu.ukma.springers.voltstore.inventory.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockControllerTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String URL = "/inventory/stock/" + PRODUCT_ID + "/adjust";

    private static final HttpHeaders WORKER = TrustedHeaders.asUser("INVENTORY_WORKER");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void adjust_validDelta_returns200WithNewQuantity() throws Exception {
        when(stockService.adjustStock(PRODUCT_ID, 5)).thenReturn(12);

        mockMvc.perform(post(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAvailableQuantity").value(12));
    }

    @Test
    void adjust_zeroDelta_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.delta[0]").value("Delta must be non-zero"));

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_missingDelta_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.delta").isNotEmpty());
    }

    @Test
    void adjust_nonIntegerDelta_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":\"abc\"}"))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_nonUuidProductId_returns400() throws Exception {
        mockMvc.perform(post("/inventory/stock/not-a-uuid/adjust").headers(WORKER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1}"))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_insufficientStock_returns422() throws Exception {
        when(stockService.adjustStock(PRODUCT_ID, -9)).thenThrow(new InsufficientStockException(PRODUCT_ID));

        mockMvc.perform(post(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-9}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient available stock for write-off"));
    }

    @Test
    void adjust_anonymous_returns401Problem() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.service").value("inventory-service"));

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "CUSTOMER"})
    void adjust_wrongRole_returns403Problem(String role) throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser(role))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.service").value("inventory-service"));

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_wrongRoleWithInvalidBody_returns403BeforeValidation() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":0}"))
                .andExpect(status().isForbidden());
    }
}
