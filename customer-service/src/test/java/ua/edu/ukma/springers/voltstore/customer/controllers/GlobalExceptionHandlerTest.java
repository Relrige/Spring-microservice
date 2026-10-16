package ua.edu.ukma.springers.voltstore.customer.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartItemNotFoundException;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolationException;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class})
class GlobalExceptionHandlerTest {
    private static final UUID PRODUCT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.quantity").isNotEmpty())
                .andExpect(jsonPath("$.errors.productId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("customer-service"));
    }

    @Test
    void malformedJson_returns400WithErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.body").isNotEmpty())
                .andExpect(jsonPath("$.service").value("customer-service"));
    }

    @Test
    void nonUuidPathVariable_returns400WithErrors() throws Exception {
        mockMvc.perform(get("/probe/not-a-uuid/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.productId[0]").value("must be a valid UUID"))
                .andExpect(jsonPath("$.service").value("customer-service"));
    }

    @Test
    void cartItemNotFound_returns404() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andExpect(jsonPath("$.service").value("customer-service"));
    }

    @Test
    void tooManyProducts_returns422WithCountAndMax() throws Exception {
        mockMvc.perform(get("/probe/too-many"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/cart-rule-violation"))
                .andExpect(jsonPath("$.violation").value("TOO_MANY_PRODUCTS"))
                .andExpect(jsonPath("$.resultingProductCount").value(11))
                .andExpect(jsonPath("$.maxProducts").value(10))
                .andExpect(jsonPath("$.service").value("customer-service"));
    }

    @Test
    void quantityLimitExceeded_returns422WithProductsAndMax() throws Exception {
        mockMvc.perform(get("/probe/quantity"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.productIds[0]").value(PRODUCT_A.toString()))
                .andExpect(jsonPath("$.productIds[1]").value(PRODUCT_B.toString()))
                .andExpect(jsonPath("$.maxQuantity").value(10));
    }

    @Test
    void duplicateProduct_returns422WithProducts() throws Exception {
        mockMvc.perform(get("/probe/duplicate"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violation").value("DUPLICATE_PRODUCT"))
                .andExpect(jsonPath("$.productIds.length()").value(1))
                .andExpect(jsonPath("$.productIds[0]").value(PRODUCT_A.toString()));
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    record ProbeRequest(@NotNull UUID productId, @NotNull @Min(1) Integer quantity) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/body")
        void body(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/{productId}/typed")
        void typed(@PathVariable UUID productId) {
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new CartItemNotFoundException(PRODUCT_A);
        }

        @GetMapping("/probe/too-many")
        void tooMany() {
            throw CartRuleViolationException.tooManyProducts(11);
        }

        @GetMapping("/probe/quantity")
        void quantity() {
            throw CartRuleViolationException.quantityLimitExceeded(List.of(PRODUCT_B, PRODUCT_A));
        }

        @GetMapping("/probe/duplicate")
        void duplicate() {
            throw CartRuleViolationException.duplicateProduct(List.of(PRODUCT_A, PRODUCT_A));
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
