package ua.edu.ukma.springers.voltstore.inventory.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

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
class StockControllerTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String URL = "/inventory/stock/" + PRODUCT_ID + "/adjust";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void adjust_validDelta_returns200WithNewQuantity() throws Exception {
        when(stockService.adjustStock(PRODUCT_ID, 5)).thenReturn(12);

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAvailableQuantity").value(12));
    }

    @Test
    void adjust_zeroDelta_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.delta[0]").value("Delta must be non-zero"));

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_missingDelta_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.delta").isNotEmpty());
    }

    @Test
    void adjust_nonIntegerDelta_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":\"abc\"}"))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_nonUuidProductId_returns400() throws Exception {
        mockMvc.perform(post("/inventory/stock/not-a-uuid/adjust")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":1}"))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).adjustStock(any(), anyInt());
    }

    @Test
    void adjust_insufficientStock_returns422() throws Exception {
        when(stockService.adjustStock(PRODUCT_ID, -9)).thenThrow(new InsufficientStockException(PRODUCT_ID));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"delta\":-9}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient available stock for write-off"));
    }
}
