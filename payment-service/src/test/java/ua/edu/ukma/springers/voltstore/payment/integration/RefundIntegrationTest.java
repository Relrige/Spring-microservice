package ua.edu.ukma.springers.voltstore.payment.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.payment.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyRefundedException;
import ua.edu.ukma.springers.voltstore.payment.gateway.MockPaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.services.PaymentService;
import ua.edu.ukma.springers.voltstore.payment.support.Concurrently;
import ua.edu.ukma.springers.voltstore.payment.support.InternalHeaders;
import ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EP-PAY-02 end to end: charge with the mock gateway, then refund it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefundIntegrationTest {
    private static final String AMOUNT = "1499.90";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private MockPaymentGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID orderId = UUID.randomUUID();
    private int refundsBefore;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM payment_transactions");
        refundsBefore = gateway.approvedRefundCount();
    }

    @Test
    void chargeThenRefund_succeeds_andASecondRefundIs409() throws Exception {
        chargeOrder();

        refund(AMOUNT).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        refund(AMOUNT).andExpect(status().isConflict());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM payment_transactions WHERE order_id = ? AND type = 'REFUND'", orderId);
        assertThat(row).containsEntry("status", "SUCCESS").containsEntry("idempotency_key", "refund:" + orderId);
        assertThat((String) row.get("gateway_transaction_id")).startsWith("mock_re_");
        assertThat(refundedSinceStart()).isEqualTo(1);
    }

    @Test
    void refundWithoutACharge_is422() throws Exception {
        refund(AMOUNT)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "No successful charge found for order " + orderId + " - cannot refund"));

        assertThat(refundedSinceStart()).isZero();
    }

    @Test
    void refundAfterOnlyADeclinedCharge_is422() throws Exception {
        mockMvc.perform(post("/payments/charge").headers(InternalHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PaymentRequests.charge(orderId, PaymentRequests.INSUFFICIENT_FUNDS_CARD)))
                .andExpect(status().isPaymentRequired());

        refund(AMOUNT).andExpect(status().isUnprocessableContent());
    }

    @Test
    void partialRefund_is422_andNothingIsRefunded() throws Exception {
        chargeOrder();

        refund("100.00").andExpect(status().isUnprocessableContent());

        assertThat(refundedSinceStart()).isZero();
        refund(AMOUNT).andExpect(status().isOk());
    }

    @Test
    void concurrentRefunds_ofOneOrder_refundOnce() throws Exception {
        chargeOrder();

        List<Object> results = Concurrently.run(10,
                () -> paymentService.refund(orderId, new BigDecimal(AMOUNT), "UAH"));

        assertThat(results).filteredOn(UUID.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(result -> !(result instanceof UUID))
                .allSatisfy(result -> assertThat(result).isInstanceOf(OrderAlreadyRefundedException.class));
        assertThat(refundedSinceStart()).isEqualTo(1);
    }

    private void chargeOrder() throws Exception {
        mockMvc.perform(post("/payments/charge").headers(InternalHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PaymentRequests.charge(orderId, AMOUNT, PaymentRequests.APPROVED_CARD)))
                .andExpect(status().isOk());
    }

    private ResultActions refund(String amount) throws Exception {
        return mockMvc.perform(post("/payments/refund").headers(InternalHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON).content(PaymentRequests.refund(orderId, amount)));
    }

    private int refundedSinceStart() {
        return gateway.approvedRefundCount() - refundsBefore;
    }
}
