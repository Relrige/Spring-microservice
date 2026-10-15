package ua.edu.ukma.springers.voltstore.payment.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.exceptions.NoSuccessfulChargeException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyChargedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyRefundedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayUnavailableException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundAmountMismatchException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundRejectedException;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentCard;
import ua.edu.ukma.springers.voltstore.payment.security.SecurityConfig;
import ua.edu.ukma.springers.voltstore.payment.services.PaymentService;
import ua.edu.ukma.springers.voltstore.payment.support.InternalHeaders;
import ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRANSACTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final BigDecimal AMOUNT = new BigDecimal("1499.90");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void charge_success_returnsOurTransactionId() throws Exception {
        when(paymentService.charge(any(), any(), any(), any())).thenReturn(TRANSACTION_ID);

        charge(PaymentRequests.charge(ORDER_ID, PaymentRequests.APPROVED_CARD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(paymentService).charge(eq(ORDER_ID), eq(AMOUNT), eq("UAH"), argThat((PaymentCard card) ->
                card.number().equals(PaymentRequests.APPROVED_CARD) && card.expiryMonth() == 12
                        && card.expiryYear() == 2035 && card.cvv().equals(PaymentRequests.CVV)));
    }

    @Test
    void charge_cardExpiringThisMonth_isAccepted() throws Exception {
        YearMonth now = YearMonth.now(ZoneId.of("Europe/Kyiv"));

        charge(chargeWithCard("4242424242424242", now.getMonthValue(), now.getYear(), "987"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            missing orderId      | {"amount":10,"currency":"UAH","cardDetails":%s}                                | orderId
            zero amount          | {"orderId":"%s","amount":0,"currency":"UAH","cardDetails":%s}                   | amount
            three decimals       | {"orderId":"%s","amount":10.001,"currency":"UAH","cardDetails":%s}              | amount
            other currency       | {"orderId":"%s","amount":10,"currency":"USD","cardDetails":%s}                  | currency
            missing card         | {"orderId":"%s","amount":10,"currency":"UAH"}                                   | cardDetails
            """)
    void charge_invalidOrderFields_are400(String name, String template, String field) throws Exception {
        String card = "{\"number\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2035,\"cvv\":\"987\"}";
        String body = template.startsWith("{\"amount\"") ? template.formatted(card) : template.formatted(ORDER_ID, card);

        charge(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors." + field).isNotEmpty());
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            12 digits            | 424242424242        | 12 | 2035 | 987   | cardDetails.number
            20 digits            | 42424242424242424242 | 12 | 2035 | 987   | cardDetails.number
            letters              | 4242abcd42424242    | 12 | 2035 | 987   | cardDetails.number
            month 13             | 4242424242424242    | 13 | 2035 | 987   | cardDetails.expiryMonth
            expired              | 4242424242424242    | 1  | 2020 | 987   | cardDetails
            2-digit cvv          | 4242424242424242    | 12 | 2035 | 98    | cardDetails.cvv
            5-digit cvv          | 4242424242424242    | 12 | 2035 | 98765 | cardDetails.cvv
            """)
    void charge_invalidCard_is400_withoutEchoingIt(String name, String number, int month, int year, String cvv,
                                                    String field) throws Exception {
        String response = charge(chargeWithCard(number, month, year, cvv))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['" + field + "']").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(number).doesNotContain("\"" + cvv + "\"");
        verifyNoInteractions(paymentService);
    }

    @Test
    void charge_declined_is402WithReason() throws Exception {
        when(paymentService.charge(any(), any(), any(), any()))
                .thenThrow(new PaymentDeclinedException(FailureReason.INSUFFICIENT_FUNDS));

        charge(PaymentRequests.charge(ORDER_ID, PaymentRequests.APPROVED_CARD))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.reason").value("insufficient_funds"));
    }

    @Test
    void charge_alreadyCharged_is409() throws Exception {
        when(paymentService.charge(any(), any(), any(), any())).thenThrow(new OrderAlreadyChargedException(ORDER_ID));

        charge(PaymentRequests.charge(ORDER_ID, PaymentRequests.APPROVED_CARD)).andExpect(status().isConflict());
    }

    @Test
    void charge_attemptInProgress_is409() throws Exception {
        when(paymentService.charge(any(), any(), any(), any()))
                .thenThrow(new PaymentAttemptInProgressException(ORDER_ID));

        charge(PaymentRequests.charge(ORDER_ID, PaymentRequests.APPROVED_CARD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payment attempt in progress"));
    }

    @Test
    void charge_gatewayTimeout_is504() throws Exception {
        when(paymentService.charge(any(), any(), any(), any()))
                .thenThrow(new PaymentGatewayTimeoutException("no answer"));

        charge(PaymentRequests.charge(ORDER_ID, PaymentRequests.APPROVED_CARD)).andExpect(status().isGatewayTimeout());
    }

    @Test
    void charge_gatewayUnavailable_is502() throws Exception {
        when(paymentService.charge(any(), any(), any(), any()))
                .thenThrow(new PaymentGatewayUnavailableException("refused", new ConnectException()));

        charge(PaymentRequests.charge(ORDER_ID, PaymentRequests.APPROVED_CARD)).andExpect(status().isBadGateway());
    }

    @Test
    void refund_success_returnsOurTransactionId() throws Exception {
        when(paymentService.refund(ORDER_ID, AMOUNT, "UAH")).thenReturn(TRANSACTION_ID);

        refund(PaymentRequests.refund(ORDER_ID, "1499.90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void refund_invalidBody_is400() throws Exception {
        refund("{\"orderId\":\"" + ORDER_ID + "\",\"amount\":-5,\"currency\":\"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").isNotEmpty())
                .andExpect(jsonPath("$.errors.currency").isNotEmpty());
        verifyNoInteractions(paymentService);
    }

    @Test
    void refund_withoutCharge_is422() throws Exception {
        when(paymentService.refund(any(), any(), any())).thenThrow(new NoSuccessfulChargeException(ORDER_ID));

        refund(PaymentRequests.refund(ORDER_ID, "1499.90")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void refund_partial_is422() throws Exception {
        when(paymentService.refund(any(), any(), any()))
                .thenThrow(new RefundAmountMismatchException(ORDER_ID, AMOUNT, "UAH"));

        refund(PaymentRequests.refund(ORDER_ID, "10.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Only full refunds are supported"));
    }

    @Test
    void refund_alreadyRefunded_is409() throws Exception {
        when(paymentService.refund(any(), any(), any())).thenThrow(new OrderAlreadyRefundedException(ORDER_ID));

        refund(PaymentRequests.refund(ORDER_ID, "1499.90")).andExpect(status().isConflict());
    }

    @Test
    void refund_rejected_is502WithReason() throws Exception {
        when(paymentService.refund(any(), any(), any())).thenThrow(new RefundRejectedException(ORDER_ID));

        refund(PaymentRequests.refund(ORDER_ID, "1499.90"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.reason").value("refund_rejected"));
    }

    private ResultActions charge(String body) throws Exception {
        return mockMvc.perform(post("/payments/charge").headers(InternalHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions refund(String body) throws Exception {
        return mockMvc.perform(post("/payments/refund").headers(InternalHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String chargeWithCard(String number, int month, int year, String cvv) {
        return """
                {"orderId":"%s","amount":10,"currency":"UAH",
                 "cardDetails":{"number":"%s","expiryMonth":%d,"expiryYear":%d,"cvv":"%s"}}"""
                .formatted(ORDER_ID, number, month, year, cvv);
    }
}
