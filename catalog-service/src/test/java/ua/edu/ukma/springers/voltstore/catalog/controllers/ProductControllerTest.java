package ua.edu.ukma.springers.voltstore.catalog.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {
    private static final String URL = "/catalog/products";
    private static final UUID CATEGORY_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void create_valid_returns201WithProductId() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.createProduct(any())).thenReturn(new CreateProductResponse(id));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "199.99")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(id.toString()));
    }

    @Test
    void create_blankFieldsAndMissingValues_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \",\"description\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").isNotEmpty())
                .andExpect(jsonPath("$.errors.description").isNotEmpty())
                .andExpect(jsonPath("$.errors.categoryId").isNotEmpty())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());

        verify(productService, never()).createProduct(any());
    }

    @Test
    void create_zeroPrice_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());
    }

    @Test
    void create_negativePrice_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());
    }

    @Test
    void create_priceWithThreeDecimals_returns400InsteadOfRounding() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10.999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());

        verify(productService, never()).createProduct(any());
    }

    @Test
    void create_categoryIdNotAUuid_returns400WithFieldError() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phone\",\"description\":\"A phone\",\"categoryId\":\"abc\",\"basePrice\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.categoryId").isNotEmpty());
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/bad-request"))
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    @Test
    void create_unknownCategory_returns422() throws Exception {
        when(productService.createProduct(any())).thenThrow(new CategoryNotFoundForProductException(CATEGORY_ID));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/unprocessable-entity"))
                .andExpect(jsonPath("$.title").value("Category not found"));
    }

    @Test
    void update_valid_returns200WithProduct() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.updateProduct(eq(id), any())).thenReturn(new ProductResponse(id, "Phone", "A phone",
                CATEGORY_ID, new BigDecimal("10.00"), ProductStatus.ACTIVE, StockStatus.IN_STOCK));

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(id.toString()))
                .andExpect(jsonPath("$.basePrice").value(10.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.updateProduct(eq(id), any())).thenThrow(new ProductNotFoundException(id));

        mockMvc.perform(put(URL + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_nonUuidId_returns400() throws Exception {
        mockMvc.perform(put(URL + "/123").contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        when(productService.createProduct(any())).thenThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void deleteProduct_isNotSupported() throws Exception {
        // Hard deletes are forbidden: there is no DELETE endpoint for products
        mockMvc.perform(delete(URL + "/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    static String json(String title, String description, UUID categoryId, String basePrice) {
        return "{\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"categoryId\":\""
                + categoryId + "\",\"basePrice\":" + basePrice + "}";
    }
}
