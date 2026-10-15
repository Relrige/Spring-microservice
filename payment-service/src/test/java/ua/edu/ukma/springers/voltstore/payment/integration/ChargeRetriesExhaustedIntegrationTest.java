package ua.edu.ukma.springers.voltstore.payment.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.payment.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.payment.gateway.MockPaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.support.InternalHeaders;
import ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests.APPROVED_CARD;
import static ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests.LOST_ANSWER_CARD;

/**
 * Task 10, the case where Payment Service's own repeats do not settle an unknown outcome (here: no repeats at all).
 * The request then ends with 504, and a later repeat by Order Service must still be safe: it reuses the key, so the
 * provider replays the approval instead of charging again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "payment.gateway.max-retries=0")
class ChargeRetriesExhaustedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
    void unsettledTimeout_is504_andALaterRepeat_getsTheApprovalWithoutChargingAgain() throws Exception {
        charge(LOST_ANSWER_CARD).andExpect(status().isGatewayTimeout());
        assertThat(chargedSinceStart()).as("the provider took the money although we got no answer").isEqualTo(1);

        charge(LOST_ANSWER_CARD).andExpect(status().isOk());

        assertThat(chargedSinceStart()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT coalesce(failure_reason, status) || ' ' || idempotency_key "
                + "FROM payment_transactions WHERE order_id = ? ORDER BY created_at", String.class, orderId))
                .as("the timeout did not end the attempt, so the repeat reused its key")
                .containsExactly("GATEWAY_TIMEOUT charge:" + orderId + ":1", "SUCCESS charge:" + orderId + ":1");
    }

    @Test
    void afterAnUnsettledTimeout_anotherCard_is409_untilTheSameRequestIsRepeated() throws Exception {
        charge(LOST_ANSWER_CARD).andExpect(status().isGatewayTimeout());

        charge(APPROVED_CARD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payment attempt in progress"));
        charge(LOST_ANSWER_CARD).andExpect(status().isOk());

        assertThat(chargedSinceStart()).isEqualTo(1);
    }

    private ResultActions charge(String cardNumber) throws Exception {
        return mockMvc.perform(post("/payments/charge").headers(InternalHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON).content(PaymentRequests.charge(orderId, cardNumber)));
    }

    private int chargedSinceStart() {
        return gateway.approvedChargeCount() - chargesBefore;
    }
}
