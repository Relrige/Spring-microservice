package ua.edu.ukma.springers.voltstore.payment.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.payment.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.payment.support.InternalHeaders;
import ua.edu.ukma.springers.voltstore.payment.support.PaymentRequests;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 08: card numbers and CVVs pass through this service but must never be written anywhere. Runs every mock card
 * and a rejected card through the real application and checks the log output, the responses and the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CardDataLeakIntegrationTest {
    private static final String CVV = PaymentRequests.CVV;
    private static final String INVALID_CARD = "4111111111111111999";
    private static final String INVALID_CVV = "55555";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM payment_transactions");
    }

    @Test
    void cardNumbersAndCvv_appearNeitherInLogsNorResponsesNorTheDatabase(CapturedOutput output) throws Exception {
        StringBuilder responses = new StringBuilder();
        for (String card : List.of(PaymentRequests.APPROVED_CARD, PaymentRequests.INSUFFICIENT_FUNDS_CARD,
                PaymentRequests.LOST_ANSWER_CARD, "5555555555554444")) {
            responses.append(charge(PaymentRequests.charge(UUID.randomUUID(), card)));
        }
        responses.append(charge("""
                {"orderId":"%s","amount":10,"currency":"UAH",
                 "cardDetails":{"number":"%s","expiryMonth":12,"expiryYear":2035,"cvv":"%s"}}"""
                .formatted(UUID.randomUUID(), INVALID_CARD, INVALID_CVV)));

        // CVVs quoted as they were sent: three bare digits could occur by chance inside a logged UUID
        List<String> secrets = List.of(PaymentRequests.APPROVED_CARD, PaymentRequests.INSUFFICIENT_FUNDS_CARD,
                PaymentRequests.LOST_ANSWER_CARD, "5555555555554444", INVALID_CARD,
                "\"" + CVV + "\"", "\"" + INVALID_CVV + "\"");
        for (String secret : secrets) {
            assertThat(output.getAll()).as("log output").doesNotContain(secret);
            assertThat(responses.toString()).as("responses").doesNotContain(secret);
        }
        // The calls did reach the gateway, which logs the card masked only
        assertThat(output.getAll()).contains("**** **** **** 4242", "**** **** **** 5000");

        String tableDump = String.join("\n", jdbcTemplate.queryForList(
                "SELECT t::text FROM payment_transactions t", String.class));
        assertThat(tableDump).isNotEmpty();
        // Full numbers only: short values such as a CVV could occur by chance inside the stored UUIDs
        for (String secret : List.of(PaymentRequests.APPROVED_CARD, PaymentRequests.INSUFFICIENT_FUNDS_CARD,
                PaymentRequests.LOST_ANSWER_CARD)) {
            assertThat(tableDump).as("stored rows").doesNotContain(secret);
        }
        assertThat(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'payment_transactions'", String.class))
                .noneMatch(column -> column.contains("card") || column.contains("cvv"));
    }

    private String charge(String body) throws Exception {
        return mockMvc.perform(post("/payments/charge").headers(InternalHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }
}
