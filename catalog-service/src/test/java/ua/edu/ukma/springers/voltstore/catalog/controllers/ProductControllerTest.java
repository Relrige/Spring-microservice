package ua.edu.ukma.springers.voltstore.catalog.controllers;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.catalog.dto.BatchProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.CreateProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductDetailsResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductResponse;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSearchQuery;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductStatusRequest;
import ua.edu.ukma.springers.voltstore.catalog.dto.ProductSummaryResponse;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.CategoryNotFoundForProductException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.InventoryUnavailableException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductActivationRejectedException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.ProductNotFoundException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.RequestValidationException;
import ua.edu.ukma.springers.voltstore.catalog.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.catalog.security.UserRole;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asInternalService;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asManager;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asUser;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
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

        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "199.99")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(id.toString()));
    }

    @Test
    void create_blankFieldsAndMissingValues_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \",\"description\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").isNotEmpty())
                .andExpect(jsonPath("$.errors.description").isNotEmpty())
                .andExpect(jsonPath("$.errors.categoryId").isNotEmpty())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());

        verify(productService, never()).createProduct(any());
    }

    @Test
    void create_zeroPrice_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());
    }

    @Test
    void create_negativePrice_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());
    }

    @Test
    void create_priceWithThreeDecimals_returns400InsteadOfRounding() throws Exception {
        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10.999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.basePrice").isNotEmpty());

        verify(productService, never()).createProduct(any());
    }

    @Test
    void create_categoryIdNotAUuid_returns400WithFieldError() throws Exception {
        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Phone\",\"description\":\"A phone\",\"categoryId\":\"abc\",\"basePrice\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.categoryId").isNotEmpty());
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/bad-request"))
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    @Test
    void create_unknownCategory_returns422() throws Exception {
        when(productService.createProduct(any())).thenThrow(new CategoryNotFoundForProductException(CATEGORY_ID));

        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/unprocessable-entity"))
                .andExpect(jsonPath("$.title").value("Category not found"));
    }

    @Test
    void update_valid_returns200WithProduct() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.updateProduct(eq(id), any())).thenReturn(new ProductResponse(id, "Phone", "A phone",
                CATEGORY_ID, new BigDecimal("10.00"), ProductStatus.ACTIVE, StockStatus.IN_STOCK));

        mockMvc.perform(put(URL + "/" + id).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
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

        mockMvc.perform(put(URL + "/" + id).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_nonUuidId_returns400() throws Exception {
        mockMvc.perform(put(URL + "/123").headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        when(productService.createProduct(any())).thenThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(post(URL).headers(asManager()).contentType(MediaType.APPLICATION_JSON).content(json("Phone", "A phone", CATEGORY_ID, "10")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void deleteProduct_isNotSupported() throws Exception {
        // Hard deletes are forbidden: there is no DELETE endpoint for products, so there is no security rule
        // for it either, and the fail-closed chain denies it before MVC could answer 405
        mockMvc.perform(delete(URL + "/" + UUID.randomUUID()).headers(asManager()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    // --- EP-CAT-03: change status ---

    @Test
    void changeStatus_valid_returns200WithoutBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch(URL + "/" + id + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        ArgumentCaptor<ProductStatusRequest> request = ArgumentCaptor.forClass(ProductStatusRequest.class);
        verify(productService).changeStatus(eq(id), request.capture());
        assertThat(request.getValue().getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void changeStatus_unknownStatusValue_returns400WithFieldError() throws Exception {
        mockMvc.perform(patch(URL + "/" + UUID.randomUUID() + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").isNotEmpty());

        verify(productService, never()).changeStatus(any(), any());
    }

    @Test
    void changeStatus_unreadableBody_returns400() throws Exception {
        mockMvc.perform(patch(URL + "/" + UUID.randomUUID() + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/bad-request"));
    }

    @Test
    void changeStatus_missingStatus_returns400FromServiceValidation() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new RequestValidationException(Map.of("status", List.of("must not be null"))))
                .when(productService).changeStatus(eq(id), any());

        mockMvc.perform(patch(URL + "/" + id + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status[0]").value("must not be null"));
    }

    @Test
    void changeStatus_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ProductNotFoundException(id)).when(productService).changeStatus(eq(id), any());

        mockMvc.perform(patch(URL + "/" + id + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NOT_ACTIVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeStatus_activationRejected_returns422WithReason() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ProductActivationRejectedException(id, "packaging_data_missing"))
                .when(productService).changeStatus(eq(id), any());

        mockMvc.perform(patch(URL + "/" + id + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/activation-rejected"))
                .andExpect(jsonPath("$.reason").value("packaging_data_missing"))
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    @Test
    void changeStatus_inventoryUnavailable_returns503WithRetryAfterAndNoInternals() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new InventoryUnavailableException("Inventory Service is unavailable",
                new IllegalStateException("connect to inventory-service:8084 refused")))
                .when(productService).changeStatus(eq(id), any());

        mockMvc.perform(patch(URL + "/" + id + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/service-unavailable"))
                .andExpect(jsonPath("$.detail").value("Stock readiness cannot be checked right now. Please try again later."))
                .andExpect(content().string(not(containsString("8084"))));
    }

    // --- EP-CAT-05: product details ---

    @Test
    void getProduct_anonymous_returnsCustomerViewWithoutStatus() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.getActiveProduct(id)).thenReturn(new ProductDetailsResponse(id, "Phone", "A phone",
                CATEGORY_ID, new BigDecimal("10.00"), StockStatus.IN_STOCK));

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(id.toString()))
                .andExpect(jsonPath("$.description").value("A phone"))
                .andExpect(jsonPath("$.categoryId").value(CATEGORY_ID.toString()))
                .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void getProduct_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.getActiveProduct(id)).thenThrow(new ProductNotFoundException(id));

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Product with ID " + id + " not found"));
    }

    @Test
    void getProduct_nonUuidId_returns400() throws Exception {
        mockMvc.perform(get(URL + "/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // --- EP-CAT-04: browse ---

    @Test
    void browse_anonymous_returnsEnvelopeAndPassesNoRole() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.browseProducts(any(), any())).thenReturn(new PageResponse<>(List.of(
                new ProductSummaryResponse(id, "Phone", new BigDecimal("10.00"), CATEGORY_ID,
                        ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK)), 0, 20, 1));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value(id.toString()))
                .andExpect(jsonPath("$.content[0].basePrice").value(10.00))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.content[0].description").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(productService).browseProducts(any(), isNull());
    }

    @Test
    void browse_passesTheRoleFromTheTrustedHeaders() throws Exception {
        when(productService.browseProducts(any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 20, 0));

        mockMvc.perform(get(URL).headers(asUser("CUSTOMER"))).andExpect(status().isOk());
        mockMvc.perform(get(URL).headers(asManager())).andExpect(status().isOk());
        // A service call carries no user
        mockMvc.perform(get(URL).headers(asInternalService())).andExpect(status().isOk());

        verify(productService).browseProducts(any(), eq(UserRole.CUSTOMER));
        verify(productService).browseProducts(any(), eq(UserRole.CATALOG_MANAGER));
        verify(productService).browseProducts(any(), isNull());
    }

    @Test
    void browse_bindsQueryParameters() throws Exception {
        when(productService.browseProducts(any(), any())).thenReturn(new PageResponse<>(List.of(), 2, 5, 0));

        mockMvc.perform(get(URL).headers(asManager())
                        .param("search", "phone").param("categoryId", CATEGORY_ID.toString())
                        .param("minPrice", "10").param("maxPrice", "99.5").param("sortBy", "basePrice,desc")
                        .param("status", "NOT_ACTIVE").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductSearchQuery> query = ArgumentCaptor.forClass(ProductSearchQuery.class);
        verify(productService).browseProducts(query.capture(), eq(UserRole.CATALOG_MANAGER));
        ProductSearchQuery bound = query.getValue();
        assertThat(bound.getSearch()).isEqualTo("phone");
        assertThat(bound.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(bound.getMinPrice()).isEqualByComparingTo("10");
        assertThat(bound.getMaxPrice()).isEqualByComparingTo("99.5");
        assertThat(bound.getSortBy()).isEqualTo("basePrice,desc");
        assertThat(bound.getStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        assertThat(bound.getPage()).isEqualTo(2);
        assertThat(bound.getSize()).isEqualTo(5);
    }

    @Test
    void browse_invalidParameters_return400WithFieldErrors() throws Exception {
        String[][] invalid = {
                {"status", "DELETED"}, {"page", "-1"}, {"page", "abc"}, {"size", "0"}, {"size", "101"},
                {"minPrice", "cheap"}, {"maxPrice", "-1"}, {"categoryId", "not-a-uuid"},
                {"page", "30000000"}, {"minPrice", "1E200000"}, {"maxPrice", "10.999"}
        };
        for (String[] param : invalid) {
            mockMvc.perform(get(URL).param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                    .andExpect(jsonPath("$.errors." + param[0]).isNotEmpty());
        }
        verify(productService, never()).browseProducts(any(), any());
    }

    @Test
    void browse_serviceRuleViolation_returns400() throws Exception {
        when(productService.browseProducts(any(), any()))
                .thenThrow(new RequestValidationException(Map.of("minPrice", List.of("must not be greater than maxPrice"))));

        mockMvc.perform(get(URL).param("minPrice", "10").param("maxPrice", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minPrice[0]").value("must not be greater than maxPrice"));
    }

    // --- EP-CAT-09: batch get ---

    @Test
    void batchGet_asService_parsesCommaSeparatedIdsAndReturnsFlatArray() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(productService.batchGetProducts(List.of(first, second))).thenReturn(List.of(
                new BatchProductResponse(first, "Phone", new BigDecimal("10.00"), ProductStatus.NOT_ACTIVE,
                        StockStatus.OUT_OF_STOCK)));

        mockMvc.perform(get(URL + "/batch-get").headers(asInternalService()).param("ids", first + "," + second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(first.toString()))
                .andExpect(jsonPath("$[0].title").value("Phone"))
                .andExpect(jsonPath("$[0].basePrice").value(10.00))
                .andExpect(jsonPath("$[0].status").value("NOT_ACTIVE"))
                .andExpect(jsonPath("$[0].stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void batchGet_isNotAnsweredByTheProductDetailsHandler() throws Exception {
        when(productService.batchGetProducts(any())).thenReturn(List.of());

        mockMvc.perform(get(URL + "/batch-get").headers(asInternalService()).param("ids", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(productService, never()).getActiveProduct(any());
    }

    @Test
    void batchGet_malformedUuid_returns400() throws Exception {
        mockMvc.perform(get(URL + "/batch-get").headers(asInternalService())
                        .param("ids", UUID.randomUUID() + ",not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(productService, never()).batchGetProducts(any());
    }

    @Test
    void batchGet_missingIds_returns400() throws Exception {
        mockMvc.perform(get(URL + "/batch-get").headers(asInternalService()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    @Test
    void batchGet_serviceValidationError_returns400() throws Exception {
        when(productService.batchGetProducts(any()))
                .thenThrow(new RequestValidationException(Map.of("ids", List.of("must be a non-empty list of UUIDs"))));

        mockMvc.perform(get(URL + "/batch-get").headers(asInternalService()).param("ids", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").isNotEmpty());
    }

    static String json(String title, String description, UUID categoryId, String basePrice) {
        return "{\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"categoryId\":\""
                + categoryId + "\",\"basePrice\":" + basePrice + "}";
    }
}
