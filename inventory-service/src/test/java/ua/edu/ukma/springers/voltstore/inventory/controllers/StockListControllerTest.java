package ua.edu.ukma.springers.voltstore.inventory.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockListControllerTest {
    private static final String URL = "/inventory/stock";
    private static final HttpHeaders WORKER = TrustedHeaders.asUser("INVENTORY_WORKER");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void list_returnsEnvelopeWithComputedFields() throws Exception {
        UUID productId = UUID.randomUUID();
        StockItemEntity item = StockItemEntity.builder().productId(productId)
                .availableQuantity(7).reservedQuantity(3)
                .grossWeightGrams(500).lengthCm(10).widthCm(5).heightCm(2).build();
        when(stockService.listStock(any(), any())).thenReturn(
                new PageImpl<>(List.of(item), PageRequest.of(1, 5), 6));

        mockMvc.perform(get(URL).headers(WORKER).param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(7))
                .andExpect(jsonPath("$.content[0].reservedQuantity").value(3))
                .andExpect(jsonPath("$.content[0].totalQuantity").value(10))
                .andExpect(jsonPath("$.content[0].packagingConfigured").value(true));
    }

    @Test
    void list_noItems_returnsEmptyContent() throws Exception {
        when(stockService.listStock(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL).headers(WORKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void list_withoutParameters_usesDefaultsAndNoFilter() throws Exception {
        when(stockService.listStock(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL).headers(WORKER)).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(stockService).listStock(isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "productId"));
    }

    @Test
    void list_filterAndSort_arePassedToService() throws Exception {
        when(stockService.listStock(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL).headers(WORKER)
                        .param("packagingConfigured", "false").param("sortBy", "availableQuantity,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(stockService).listStock(org.mockito.ArgumentMatchers.eq(false), pageable.capture());
        assertThat(pageable.getValue().getSort()).isEqualTo(
                Sort.by(Sort.Order.desc("availableQuantity"), Sort.Order.asc("productId")));
    }

    @Test
    void list_invalidBoolean_returns400() throws Exception {
        mockMvc.perform(get(URL).headers(WORKER).param("packagingConfigured", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.service").value("inventory-service"));

        verify(stockService, never()).listStock(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "availableQuantity,up", "available,asc,desc", "totalQuantity"})
    void list_invalidSortBy_returns400WithFieldError(String sortBy) throws Exception {
        mockMvc.perform(get(URL).headers(WORKER).param("sortBy", sortBy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sortBy[0]").isNotEmpty());

        verify(stockService, never()).listStock(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc"})
    void list_invalidPage_returns400(String page) throws Exception {
        mockMvc.perform(get(URL).headers(WORKER).param("page", page)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-3", "101", "abc"})
    void list_invalidSize_returns400(String size) throws Exception {
        mockMvc.perform(get(URL).headers(WORKER).param("size", size)).andExpect(status().isBadRequest());
    }

    @Test
    void list_anonymous_returns401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());

        verify(stockService, never()).listStock(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "CUSTOMER"})
    void list_wrongRole_returns403(String role) throws Exception {
        mockMvc.perform(get(URL).headers(TrustedHeaders.asUser(role))).andExpect(status().isForbidden());

        verify(stockService, never()).listStock(any(), any());
    }
}
