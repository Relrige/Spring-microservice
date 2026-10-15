package ua.edu.ukma.springers.voltstore.payment.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.exceptions.NoSuccessfulChargeException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyChargedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyRefundedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayUnavailableException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    private static final String CARD_NUMBER = "4242424242424242";
    private static final String CVV = "987";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + CARD_NUMBER + "\",\"cvv\":\"" + CVV + "\",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.amount").isNotEmpty())
                .andExpect(jsonPath("$.errors.orderId").isNotEmpty())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("payment-service"));
    }

    @Test
    void rejectedCardFields_areNamedButNeverEchoed(CapturedOutput output) throws Exception {
        String invalidNumber = CARD_NUMBER + "1234";

        String body = mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"cardNumber\":\"" + invalidNumber
                                + "\",\"cvv\":\"12345\",\"amount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardNumber").isNotEmpty())
                .andExpect(jsonPath("$.errors.cvv").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(invalidNumber).doesNotContain("12345");
        assertThat(output.getAll()).doesNotContain(invalidNumber).doesNotContain("12345");
    }

    @Test
    void malformedJson_returns400WithoutRepeatingTheBody(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + CARD_NUMBER + "\",\"amount\":\"ten\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.service").value("payment-service"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CARD_NUMBER);
        assertThat(output.getAll()).doesNotContain(CARD_NUMBER);
    }

    @Test
    void paymentDeclined_returns402WithReason() throws Exception {
        mockMvc.perform(get("/probe/declined"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/payment-declined"))
                .andExpect(jsonPath("$.reason").value("insufficient_funds"))
                .andExpect(jsonPath("$.service").value("payment-service"));
    }

    @Test
    void orderAlreadyCharged_returns409() throws Exception {
        mockMvc.perform(get("/probe/already-charged"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order already charged"));
    }

    @Test
    void orderAlreadyRefunded_returns409() throws Exception {
        mockMvc.perform(get("/probe/already-refunded"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order already refunded"));
    }

    @Test
    void noSuccessfulCharge_returns422() throws Exception {
        mockMvc.perform(get("/probe/no-charge"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/no-successful-charge"));
    }

    @Test
    void gatewayUnavailable_returns502WithFixedText() throws Exception {
        mockMvc.perform(get("/probe/unavailable"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.GATEWAY_UNAVAILABLE_DETAIL));
    }

    @Test
    void gatewayTimeout_returns504WithFixedText() throws Exception {
        mockMvc.perform(get("/probe/timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.GATEWAY_TIMEOUT_DETAIL));
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.service").value("payment-service"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    record ProbeRequest(@NotNull UUID orderId,
                        @Pattern(regexp = "\\d{13,19}", message = "must be 13 to 19 digits") String cardNumber,
                        @Pattern(regexp = "\\d{3,4}", message = "must be 3 or 4 digits") String cvv,
                        @Positive BigDecimal amount) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/body")
        void body(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/declined")
        void declined() {
            throw new PaymentDeclinedException(FailureReason.INSUFFICIENT_FUNDS);
        }

        @GetMapping("/probe/already-charged")
        void alreadyCharged() {
            throw new OrderAlreadyChargedException(UUID.randomUUID());
        }

        @GetMapping("/probe/already-refunded")
        void alreadyRefunded() {
            throw new OrderAlreadyRefundedException(UUID.randomUUID());
        }

        @GetMapping("/probe/no-charge")
        void noCharge() {
            throw new NoSuccessfulChargeException(UUID.randomUUID());
        }

        @GetMapping("/probe/unavailable")
        void unavailable() {
            throw new PaymentGatewayUnavailableException("connection refused", new ConnectException());
        }

        @GetMapping("/probe/timeout")
        void timeout() {
            throw new PaymentGatewayTimeoutException("no answer");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
