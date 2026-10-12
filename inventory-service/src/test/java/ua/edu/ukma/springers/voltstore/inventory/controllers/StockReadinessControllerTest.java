package ua.edu.ukma.springers.voltstore.inventory.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;
import ua.edu.ukma.springers.voltstore.inventory.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockReadinessControllerTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String URL = "/inventory/stock/" + PRODUCT_ID + "/readiness";
    private static final HttpHeaders SERVICE = TrustedHeaders.asInternalService();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void checkReadiness_ready_returns204WithEmptyBody() throws Exception {
        mockMvc.perform(get(URL).headers(SERVICE))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(stockService).checkReadiness(PRODUCT_ID);
    }

    @Test
    void checkReadiness_missingItem_returns404ProblemDetail() throws Exception {
        doThrow(new StockItemNotFoundException(PRODUCT_ID)).when(stockService).checkReadiness(PRODUCT_ID);

        mockMvc.perform(get(URL).headers(SERVICE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.service").value("inventory-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void checkReadiness_packagingNotConfigured_returns422WithReason() throws Exception {
        doThrow(new PackagingNotConfiguredException(PRODUCT_ID)).when(stockService).checkReadiness(PRODUCT_ID);

        mockMvc.perform(get(URL).headers(SERVICE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/packaging-not-configured"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.reason").value("packaging_data_missing"))
                .andExpect(jsonPath("$.service").value("inventory-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void checkReadiness_nonUuidProductId_returns400() throws Exception {
        mockMvc.perform(get("/inventory/stock/not-a-uuid/readiness").headers(SERVICE))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).checkReadiness(any());
    }

    @Test
    void checkReadiness_anonymous_returns401() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());

        verify(stockService, never()).checkReadiness(any());
    }

    @Test
    void checkReadiness_invalidInternalToken_returns401() throws Exception {
        mockMvc.perform(get(URL).header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        verify(stockService, never()).checkReadiness(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "INVENTORY_WORKER", "CUSTOMER"})
    void checkReadiness_anyUserRole_returns403(String role) throws Exception {
        mockMvc.perform(get(URL).headers(TrustedHeaders.asUser(role)))
                .andExpect(status().isForbidden());

        verify(stockService, never()).checkReadiness(any());
    }
}
