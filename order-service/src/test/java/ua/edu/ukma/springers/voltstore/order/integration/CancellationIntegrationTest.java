package ua.edu.ukma.springers.voltstore.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.jobs.InterruptedCancellationsJob;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.support.TopicProbe;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tasks 11 and 12 end to end: the customer's {@code DELETE}, the saga against WireMock stand-ins for Inventory,
 * Delivery and Payment, and the job that finishes interrupted cancellations, on PostgreSQL and Kafka. The job is run
 * by hand; "waiting for the back-off" is done by moving the last attempt back in time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class CancellationIntegrationTest {
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String REFUND = "/payments/refund";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        registry.add("clients.inventory.url", wireMock::baseUrl);
        registry.add("clients.delivery.url", wireMock::baseUrl);
        registry.add("clients.payment.url", wireMock::baseUrl);
        registry.add("resilience4j.retry.configs.default.waitDuration", () -> "10ms");
        registry.add("resilience4j.retry.configs.default.enableRandomizedWait", () -> "false");
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private InterruptedCancellationsJob job;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Value("${order.kafka.topics.order-events}")
    private String topic;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM outbox_events");
        wireMock.stubFor(WireMock.post(WireMock.urlMatching("/inventory/reservations/.+/release"))
                .willReturn(noContent()));
        wireMock.stubFor(WireMock.delete(WireMock.urlMatching("/delivery/shipments/.+")).willReturn(noContent()));
    }

    @Test
    void cancellingAPaidOrder_releasesCancelsAndRefunds_andPublishesOrderCancelled() throws Exception {
        UUID orderId = paidOrder();
        refundAnswers(okJson("{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}"));

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), topic)) {
            cancel(orderId)
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            wireMock.verify(postRequestedFor(urlEqualTo("/inventory/reservations/" + orderId + "/release")));
            wireMock.verify(deleteRequestedFor(urlEqualTo("/delivery/shipments/" + orderId)));
            wireMock.verify(postRequestedFor(urlEqualTo(REFUND)).withRequestBody(equalToJson("""
                    {"orderId":"%s","amount":200.00,"currency":"UAH"}""".formatted(orderId))));
            assertThat(statusOf(orderId)).isEqualTo("CANCELLED");

            List<ConsumerRecord<String, String>> records = await().atMost(Duration.ofSeconds(20))
                    .until(probe::poll, received -> !received.isEmpty());
            ConsumerRecord<String, String> record = records.getFirst();
            assertThat(record.key()).isEqualTo(orderId.toString());
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("OrderCancelled");
            assertThat(JSON.readTree(record.value()).get("reason").asString()).isEqualTo("CUSTOMER_CANCELLED");
        }
    }

    @Test
    void cancellingAnUnpaidOrder_onlyReleasesTheStock() throws Exception {
        UUID orderId = repository.saveAndFlush(order()).getId();

        cancel(orderId).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLED"));

        wireMock.verify(postRequestedFor(urlEqualTo("/inventory/reservations/" + orderId + "/release")));
        wireMock.verify(0, deleteRequestedFor(WireMock.urlMatching("/delivery/shipments/.+")));
        wireMock.verify(0, postRequestedFor(urlEqualTo(REFUND)));
    }

    @Test
    void aRejectedRefund_isAccepted_andTheJobFinishesItOnItsThirdAttempt() throws Exception {
        UUID orderId = paidOrder();
        // Rejected twice (the customer's run and the job's first run), then refunded
        wireMock.stubFor(WireMock.post(urlEqualTo(REFUND)).inScenario("refund").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(rejected()).willSetStateTo("rejected once"));
        wireMock.stubFor(WireMock.post(urlEqualTo(REFUND)).inScenario("refund").whenScenarioStateIs("rejected once")
                .willReturn(rejected()).willSetStateTo("refunds"));
        wireMock.stubFor(WireMock.post(urlEqualTo(REFUND)).inScenario("refund").whenScenarioStateIs("refunds")
                .willReturn(okJson("{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}")));

        cancel(orderId)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLATION_REQUESTED"));
        Map<String, Object> afterRequest = row(orderId);
        assertThat(afterRequest.get("cancellation_attempts")).isEqualTo(1);
        assertThat(afterRequest.get("cancellation_failure")).isEqualTo("refund: refund_rejected");

        // Not due yet: the job leaves it alone
        job.retryDueCancellations();
        assertThat(row(orderId).get("cancellation_attempts")).isEqualTo(1);

        backoffElapsed();
        job.retryDueCancellations();
        assertThat(row(orderId).get("cancellation_attempts")).isEqualTo(2);
        assertThat(statusOf(orderId)).isEqualTo("CANCELLATION_REQUESTED");

        backoffElapsed();
        job.retryDueCancellations();
        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");

        wireMock.verify(3, postRequestedFor(urlEqualTo(REFUND)));
        wireMock.verify(3, postRequestedFor(urlEqualTo("/inventory/reservations/" + orderId + "/release")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'OrderCancelled'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void fiveFailures_flagTheOrderForAPerson_alertOnce_andRetriesGoOn(CapturedOutput output) throws Exception {
        UUID orderId = paidOrder();
        refundAnswers(rejected());

        cancel(orderId).andExpect(status().isAccepted());
        for (int run = 0; run < 5; run++) {
            backoffElapsed();
            job.retryDueCancellations();
        }

        Map<String, Object> row = row(orderId);
        assertThat(row.get("cancellation_attempts")).isEqualTo(6);
        assertThat(row.get("needs_manual_attention")).isEqualTo(true);
        assertThat(row.get("status")).isEqualTo("CANCELLATION_REQUESTED");
        assertThat(output.getAll().split("needs manual attention", -1)).as("one alert").hasSize(2);
    }

    @Test
    void aRepeatedDelete_whileTheCancellationIsPending_is409_andStartsNothing() throws Exception {
        UUID orderId = paidOrder();
        refundAnswers(rejected());
        cancel(orderId).andExpect(status().isAccepted());

        cancel(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.orderStatus").value("CANCELLATION_REQUESTED"));

        wireMock.verify(1, postRequestedFor(urlEqualTo(REFUND)));
    }

    @Test
    void aShippedOrder_cannotBeCancelled() throws Exception {
        OrderEntity order = order();
        order.markPaid(Instant.now());
        order.markShipped();
        UUID orderId = repository.saveAndFlush(order).getId();

        cancel(orderId).andExpect(status().isConflict()).andExpect(jsonPath("$.orderStatus").value("SHIPPED"));

        wireMock.verify(0, postRequestedFor(WireMock.urlMatching("/inventory/.+")));
    }

    @Test
    void someoneElsesOrder_is404() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(MockMvcRequestBuilders.delete("/orders/" + orderId)
                        .headers(TrustedHeaders.asCustomer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    private ResultActions cancel(UUID orderId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.delete("/orders/" + orderId)
                .headers(TrustedHeaders.asCustomer(CUSTOMER)));
    }

    // The time comes from the JVM, like the job's clock; the database's now() may differ (a drifting Docker VM)
    private void backoffElapsed() {
        jdbc.update("UPDATE orders SET cancellation_last_attempt_at = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
    }

    private void refundAnswers(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder answer) {
        wireMock.stubFor(WireMock.post(urlEqualTo(REFUND)).willReturn(answer));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder rejected() {
        return aResponse().withStatus(502).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":502,\"title\":\"Refund rejected\",\"reason\":\"refund_rejected\"}");
    }

    private Map<String, Object> row(UUID orderId) {
        return jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private UUID paidOrder() {
        OrderEntity order = order();
        order.markPaid(Instant.now());
        return repository.saveAndFlush(order).getId();
    }

    private static OrderEntity order() {
        return OrderEntity.place(UUID.randomUUID(), CUSTOMER, "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
                "UAH", Instant.now(), Duration.ofMinutes(15));
    }
}
