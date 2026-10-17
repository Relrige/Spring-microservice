package ua.edu.ukma.springers.voltstore.order.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentWindowExpiredException;
import ua.edu.ukma.springers.voltstore.order.exceptions.TtnNotGeneratedException;

import java.net.ConnectException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.items").isNotEmpty())
                .andExpect(jsonPath("$.errors.cardNumber").isNotEmpty())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    void invalidCardNumber_isNamedButNeverEchoed(CapturedOutput output) throws Exception {
        String invalidNumber = "42424242424242429999";

        String body = mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[\"x\"],\"cardNumber\":\"" + invalidNumber + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardNumber").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(invalidNumber);
        assertThat(output.getAll()).doesNotContain(invalidNumber);
    }

    @Test
    void malformedJson_returns400WithCommonFields() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    void nonUuidPathVariable_returns400() throws Exception {
        mockMvc.perform(get("/probe/not-a-uuid/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    void orderNotFound_returns404() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"));
    }

    @Test
    void ineligibleItems_returns422WithTheList() throws Exception {
        mockMvc.perform(get("/probe/ineligible"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/ineligible-items"))
                .andExpect(jsonPath("$.ineligibleItems[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.ineligibleItems[0].reason").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void insufficientStock_returns422WithInventorysList() throws Exception {
        mockMvc.perform(get("/probe/insufficient"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/insufficient-stock"))
                .andExpect(jsonPath("$.insufficientItems[0].requested").value(5))
                .andExpect(jsonPath("$.insufficientItems[0].available").value(2));
    }

    @Test
    void invalidState_returns409WithTheCurrentStatus() throws Exception {
        mockMvc.perform(get("/probe/state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.orderStatus").value("SHIPPED"));
    }

    @Test
    void paymentWindowExpired_returns409WithItsOwnType() throws Exception {
        mockMvc.perform(get("/probe/expired"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/payment-window-expired"));
    }

    @Test
    void paymentAttemptInProgress_returns409() throws Exception {
        mockMvc.perform(get("/probe/in-progress"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payment attempt in progress"));
    }

    @Test
    void paymentDeclined_returns402WithReason() throws Exception {
        mockMvc.perform(get("/probe/declined"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/payment-declined"))
                .andExpect(jsonPath("$.reason").value("insufficient_funds"));
    }

    @Test
    void ttnNotGenerated_returns409() throws Exception {
        mockMvc.perform(get("/probe/no-ttn"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("TTN not generated"));
    }

    @Test
    void dependencyUnavailable_returns503WithFixedTextAndRetryAfter(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(get("/probe/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", GlobalExceptionHandler.RETRY_AFTER_SECONDS))
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.SERVICE_UNAVAILABLE_DETAIL))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("inventory-service:8080");
        assertThat(output.getAll()).contains("inventory-service:8080");
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    record ProbeRequest(@NotEmpty List<String> items,
                        @NotNull @Pattern(regexp = "\\d{13,19}", message = "must be 13 to 19 digits")
                        String cardNumber) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/body")
        void body(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/{id}/typed")
        void typed(@PathVariable UUID id) {
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new OrderNotFoundException(ORDER_ID);
        }

        @GetMapping("/probe/ineligible")
        void ineligible() {
            throw new IneligibleItemsException(List.of(
                    new IneligibleItem(PRODUCT_ID, IneligibleItem.Reason.INSUFFICIENT_STOCK)));
        }

        @GetMapping("/probe/insufficient")
        void insufficient() {
            throw new InsufficientStockException(List.of(new InsufficientItem(PRODUCT_ID, 5, 2)));
        }

        @GetMapping("/probe/state")
        void state() {
            throw new InvalidOrderStateException(ORDER_ID, OrderStatus.SHIPPED, "be cancelled");
        }

        @GetMapping("/probe/expired")
        void expired() {
            throw new PaymentWindowExpiredException(ORDER_ID);
        }

        @GetMapping("/probe/in-progress")
        void inProgress() {
            throw new PaymentAttemptInProgressException(ORDER_ID);
        }

        @GetMapping("/probe/declined")
        void declined() {
            throw new PaymentDeclinedException("insufficient_funds");
        }

        @GetMapping("/probe/no-ttn")
        void noTtn() {
            throw new TtnNotGeneratedException(ORDER_ID);
        }

        @GetMapping("/probe/unavailable")
        void unavailable() {
            throw new DependencyUnavailableException("Inventory", "connection refused to inventory-service:8080",
                    new ConnectException());
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
