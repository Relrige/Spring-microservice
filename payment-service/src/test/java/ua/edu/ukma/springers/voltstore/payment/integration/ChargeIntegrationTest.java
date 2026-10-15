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
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyChargedException;
import ua.edu.ukma.springers.voltstore.payment.gateway.MockPaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentCard;
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
import static ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests.APPROVED_CARD;
import static ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests.INSUFFICIENT_FUNDS_CARD;
import static ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests.LOST_ANSWER_CARD;

/** EP-PAY-01 end to end: security, validation, the mock gateway's idempotency keys and PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChargeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private MockPaymentGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID orderId = UUID.randomUUID();
    private int chargesBefore;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM payment_transactions");
        chargesBefore = gateway.approvedChargeCount();
    }

    @Test
    void approvedCard_isCharged_andRecordedWithTheFirstAttemptKey() throws Exception {
        String transactionId = charge(APPROVED_CARD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"transactionId\":\"([^\"]+)\".*", "$1");

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM payment_transactions WHERE order_id = ?",
                orderId);
        assertThat(row.get("id").toString()).isEqualTo(transactionId);
        assertThat(row).containsEntry("type", "CHARGE").containsEntry("status", "SUCCESS")
                .containsEntry("idempotency_key", "charge:" + orderId + ":1");
        assertThat((String) row.get("gateway_transaction_id")).startsWith("mock_ch_");
        assertThat(chargedSinceStart()).isEqualTo(1);
    }

    @Test
    void secondCharge_ofAPaidOrder_is409_andDoesNotReachTheGateway() throws Exception {
        charge(APPROVED_CARD).andExpect(status().isOk());

        charge(APPROVED_CARD).andExpect(status().isConflict());

        assertThat(chargedSinceStart()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void afterADecline_theCustomerCanPayWithAnotherCard_underANewKey() throws Exception {
        charge(INSUFFICIENT_FUNDS_CARD)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.reason").value("insufficient_funds"));

        charge(APPROVED_CARD).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForList("SELECT status || ' ' || idempotency_key FROM payment_transactions "
                + "WHERE order_id = ? ORDER BY created_at", String.class, orderId))
                .containsExactly("FAILED charge:" + orderId + ":1", "SUCCESS charge:" + orderId + ":2");
        assertThat(chargedSinceStart()).isEqualTo(1);
    }

    @Test
    void lostAnswer_isSettledByTheInternalRepeat_withTheSameKey_andChargesOnce() throws Exception {
        // The first call times out after the provider took the money; the repeat gets the stored approval
        charge(LOST_ANSWER_CARD).andExpect(status().isOk());

        assertThat(chargedSinceStart()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT status || ' ' || idempotency_key FROM payment_transactions "
                + "WHERE order_id = ?", String.class, orderId))
                .as("only the final outcome is recorded")
                .containsExactly("SUCCESS charge:" + orderId + ":1");
    }

    @Test
    void concurrentCharges_ofOneOrder_takeTheMoneyOnce() throws Exception {
        PaymentCard card = new PaymentCard(APPROVED_CARD, 12, 2035, "987");

        List<Object> results = Concurrently.run(10,
                () -> paymentService.charge(orderId, new BigDecimal("1499.90"), "UAH", card));

        assertThat(results).filteredOn(UUID.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(result -> !(result instanceof UUID))
                .allSatisfy(result -> assertThat(result).isInstanceOf(OrderAlreadyChargedException.class));
        assertThat(chargedSinceStart()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions WHERE status = 'SUCCESS'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidRequest_isRejectedBeforeTheGateway() throws Exception {
        send(PaymentRequests.charge(orderId, "0", APPROVED_CARD)).andExpect(status().isBadRequest());

        assertThat(chargedSinceStart()).isZero();
        assertThat(rows()).isZero();
    }

    @Test
    void callerWithoutTheInternalToken_is401() throws Exception {
        mockMvc.perform(post("/payments/charge").contentType(MediaType.APPLICATION_JSON)
                        .content(PaymentRequests.charge(orderId, APPROVED_CARD)))
                .andExpect(status().isUnauthorized());

        assertThat(chargedSinceStart()).isZero();
    }

    private ResultActions charge(String cardNumber) throws Exception {
        return send(PaymentRequests.charge(orderId, cardNumber));
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post("/payments/charge").headers(InternalHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int chargedSinceStart() {
        return gateway.approvedChargeCount() - chargesBefore;
    }

    private int rows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions", Integer.class);
    }
}
