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
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;
import ua.edu.ukma.springers.voltstore.inventory.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockGetPackagingControllerTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String URL = "/inventory/stock/" + PRODUCT_ID + "/packaging";
    private static final String VALID_BODY = "{\"grossWeightGrams\":500,\"lengthCm\":10,\"widthCm\":5,\"heightCm\":2}";
    private static final HttpHeaders SERVICE = TrustedHeaders.asInternalService();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void getPackaging_configured_returns200WithAllFourValues() throws Exception {
        when(stockService.getPackaging(PRODUCT_ID)).thenReturn(StockItemEntity.builder().productId(PRODUCT_ID)
                .availableQuantity(7).grossWeightGrams(500).lengthCm(10).widthCm(5).heightCm(2).build());

        mockMvc.perform(get(URL).headers(SERVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grossWeightGrams").value(500))
                .andExpect(jsonPath("$.lengthCm").value(10))
                .andExpect(jsonPath("$.widthCm").value(5))
                .andExpect(jsonPath("$.heightCm").value(2))
                .andExpect(jsonPath("$.availableQuantity").doesNotExist());
    }

    @Test
    void getPackaging_missingItem_returns404ProblemDetail() throws Exception {
        when(stockService.getPackaging(PRODUCT_ID)).thenThrow(new StockItemNotFoundException(PRODUCT_ID));

        mockMvc.perform(get(URL).headers(SERVICE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void getPackaging_packagingNotConfigured_returns422WithReason() throws Exception {
        when(stockService.getPackaging(PRODUCT_ID)).thenThrow(new PackagingNotConfiguredException(PRODUCT_ID));

        mockMvc.perform(get(URL).headers(SERVICE))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/packaging-not-configured"))
                .andExpect(jsonPath("$.reason").value("packaging_data_missing"))
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void getPackaging_nonUuidProductId_returns400() throws Exception {
        mockMvc.perform(get("/inventory/stock/not-a-uuid/packaging").headers(SERVICE))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).getPackaging(any());
    }

    @Test
    void getPackaging_anonymous_returns401() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());

        verify(stockService, never()).getPackaging(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "INVENTORY_WORKER", "CUSTOMER"})
    void getPackaging_anyUserRole_returns403(String role) throws Exception {
        mockMvc.perform(get(URL).headers(TrustedHeaders.asUser(role)))
                .andExpect(status().isForbidden());

        verify(stockService, never()).getPackaging(any());
    }

    /**
     * GET and PUT share one path but have separate rules: each caller may use only its own method.
     */
    @Test
    void getAndPutOnSamePath_areAuthorizedIndependently() throws Exception {
        when(stockService.getPackaging(PRODUCT_ID)).thenReturn(StockItemEntity.builder().productId(PRODUCT_ID)
                .grossWeightGrams(500).lengthCm(10).widthCm(5).heightCm(2).build());
        HttpHeaders worker = TrustedHeaders.asUser("INVENTORY_WORKER");

        mockMvc.perform(get(URL).headers(SERVICE)).andExpect(status().isOk());
        mockMvc.perform(get(URL).headers(worker)).andExpect(status().isForbidden());
        mockMvc.perform(put(URL).headers(worker).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(URL).headers(SERVICE).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());

        // Only the worker's PUT reached the service
        verify(stockService, times(1)).setPackaging(any(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(stockService, times(1)).getPackaging(PRODUCT_ID);
    }
}
