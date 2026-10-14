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
import ua.edu.ukma.springers.voltstore.inventory.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class StockPackagingControllerTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String URL = "/inventory/stock/" + PRODUCT_ID + "/packaging";
    private static final String VALID_BODY = "{\"grossWeightGrams\":500,\"lengthCm\":10,\"widthCm\":5,\"heightCm\":2}";
    private static final HttpHeaders WORKER = TrustedHeaders.asUser("INVENTORY_WORKER");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    @Test
    void setPackaging_validBody_returns204WithEmptyBody() throws Exception {
        mockMvc.perform(put(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(stockService).setPackaging(PRODUCT_ID, 500, 10, 5, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"grossWeightGrams", "lengthCm", "widthCm", "heightCm"})
    void setPackaging_missingField_returns400WithFieldError(String field) throws Exception {
        String body = VALID_BODY.replaceAll("\"" + field + "\":\\d+,?", "").replace(",}", "}");

        mockMvc.perform(put(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + field).isNotEmpty());

        verify(stockService, never()).setPackaging(any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"grossWeightGrams", "lengthCm", "widthCm", "heightCm"})
    void setPackaging_zeroOrNegativeValue_returns400WithFieldError(String field) throws Exception {
        for (String value : new String[]{"0", "-1"}) {
            String body = VALID_BODY.replaceAll("\"" + field + "\":\\d+", "\"" + field + "\":" + value);

            mockMvc.perform(put(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors." + field).isNotEmpty());
        }
        verify(stockService, never()).setPackaging(any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"abc\"", "1.5", "null", "true"})
    void setPackaging_nonIntegerValue_returns400(String value) throws Exception {
        String body = VALID_BODY.replace("\"lengthCm\":10", "\"lengthCm\":" + value);

        mockMvc.perform(put(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(stockService, never()).setPackaging(any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void setPackaging_emptyBody_returns400() throws Exception {
        mockMvc.perform(put(URL).headers(WORKER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPackaging_nonUuidProductId_returns400() throws Exception {
        mockMvc.perform(put("/inventory/stock/not-a-uuid/packaging").headers(WORKER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPackaging_anonymous_returns401() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verify(stockService, never()).setPackaging(any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER", "CUSTOMER"})
    void setPackaging_wrongRole_returns403(String role) throws Exception {
        mockMvc.perform(put(URL).headers(TrustedHeaders.asUser(role))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());

        verify(stockService, never()).setPackaging(any(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void setPackaging_wrongRoleWithInvalidBody_returns403BeforeValidation() throws Exception {
        mockMvc.perform(put(URL).headers(TrustedHeaders.asUser("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void setPackaging_serviceCaller_returns403() throws Exception {
        mockMvc.perform(put(URL).headers(TrustedHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }
}
