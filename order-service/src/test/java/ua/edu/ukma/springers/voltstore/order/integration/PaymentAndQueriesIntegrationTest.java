package ua.edu.ukma.springers.voltstore.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.support.TopicProbe;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tasks 09 and 10 end to end: the real chain and services, PostgreSQL and Kafka in Testcontainers, and WireMock in
 * place of Payment and Delivery. A WireMock listener changes the order in the database while the charge request is
 * being answered, which is exactly the race the safety net handles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class PaymentAndQueriesIntegrationTest {
    private static final String CHARGE = "/payments/charge";
    private static final String REFUND = "/payments/refund";
    private static final String SHIPMENTS = "/delivery/shipments";
    private static final String CARD_NUMBER = "4111111111111111";
    private static final String CARD = """
            {"cardDetails":{"number":"%s","expiryMonth":12,"expiryYear":2099,"cvv":"737"}}""".formatted(CARD_NUMBER);
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Runs inside WireMock after the charge request arrived and before its answer is sent. */
    static volatile Runnable duringCharge = () -> {
    };

    static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort()
            .extensions(new ServeEventListener() {
                @Override
                public String getName() {
                    return "during-charge";
                }

                @Override
                public boolean applyGlobally() {
                    return false;
                }

                @Override
                public void beforeResponseSent(ServeEvent serveEvent, Parameters parameters) {
                    duringCharge.run();
                }
            }));

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        registry.add("clients.payment.url", wireMock::baseUrl);
        registry.add("clients.delivery.url", wireMock::baseUrl);
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
    private KafkaContainer kafka;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Value("${order.kafka.topics.order-events}")
    private String topic;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        duringCharge = () -> {
        };
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM outbox_events");
    }

    // ---- EP-ORD-03 ----

    @Test
    void pay_marksTheOrderPaid_andPublishesOrderPaidWithEverythingDeliveryNeeds(CapturedOutput output)
            throws Exception {
        UUID orderId = placeOrder(ALICE, Instant.now());
        wireMock.stubFor(post(urlEqualTo(CHARGE)).willReturn(okJson(
                "{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}")));

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), topic)) {
            pay(orderId, ALICE)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAID"));

            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                    .isEqualTo("PAID");
            // The amount is the order's total
            wireMock.verify(postRequestedFor(urlEqualTo(CHARGE)).withRequestBody(equalToJson("""
                    {"orderId":"%s","amount":200.00,"currency":"UAH"}""".formatted(orderId), true, true)));

            List<ConsumerRecord<String, String>> records = await().atMost(Duration.ofSeconds(20))
                    .until(probe::poll, received -> !received.isEmpty());
            ConsumerRecord<String, String> record = records.getFirst();
            assertThat(record.key()).isEqualTo(orderId.toString());
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("OrderPaid");
            JsonNode event = JSON.readTree(record.value());
            assertThat(event.get("customerId").asString()).isEqualTo(ALICE.toString());
            JsonNode delivery = event.get("deliveryDetails");
            for (String field : List.of("name", "phone", "address", "carrier")) {
                assertThat(delivery.get(field).asString()).as(field).isNotBlank();
            }
            assertThat(event.get("items")).hasSize(1);
            assertThat(event.get("totalAmount").decimalValue()).isEqualByComparingTo("200.00");
            assertThat(event.get("currency").asString()).isEqualTo("UAH");
            assertThat(event.get("paidAt").asString()).isNotBlank();
        }
        assertThat(output.getAll()).doesNotContain(CARD_NUMBER);
    }

    @Test
    void pay_whenTheOrderExpiresDuringTheCharge_refundsAtOnce_andIs409(CapturedOutput output) throws Exception {
        UUID orderId = placeOrder(ALICE, Instant.now());
        duringCharge = () -> jdbc.update("UPDATE orders SET status = 'EXPIRED', version = version + 1 WHERE id = ?",
                orderId);
        wireMock.stubFor(post(urlEqualTo(CHARGE)).withServeEventListener("during-charge", Parameters.empty()).willReturn(okJson(
                "{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}")));
        wireMock.stubFor(post(urlEqualTo(REFUND)).willReturn(okJson(
                "{\"transactionId\":\"" + UUID.randomUUID() + "\",\"status\":\"SUCCESS\"}")));

        pay(orderId, ALICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.orderStatus").value("EXPIRED"));

        wireMock.verify(postRequestedFor(urlEqualTo(REFUND)).withRequestBody(equalToJson("""
                {"orderId":"%s","amount":200.00,"currency":"UAH"}""".formatted(orderId))));
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'OrderPaid'",
                Integer.class)).isZero();
        assertThat(output.getAll()).doesNotContain(CARD_NUMBER);
    }

    @Test
    void pay_declined_is402_andTheOrderStaysPayable() throws Exception {
        UUID orderId = placeOrder(ALICE, Instant.now());
        wireMock.stubFor(post(urlEqualTo(CHARGE)).willReturn(com.github.tomakehurst.wiremock.client.WireMock
                .aResponse().withStatus(402).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":402,\"reason\":\"insufficient_funds\"}")));

        pay(orderId, ALICE)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.reason").value("insufficient_funds"));

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void pay_someoneElsesOrder_is404_withoutACharge() throws Exception {
        UUID orderId = placeOrder(ALICE, Instant.now());

        pay(orderId, BOB).andExpect(status().isNotFound());

        wireMock.verify(0, postRequestedFor(urlEqualTo(CHARGE)));
    }

    // ---- EP-ORD-04 / EP-ORD-05 ----

    @Test
    void history_showsOnlyTheCallersOrders_newestFirst_inPages() throws Exception {
        Instant now = Instant.now();
        UUID oldest = placeOrder(ALICE, now.minusSeconds(300));
        UUID middle = placeOrder(ALICE, now.minusSeconds(200));
        UUID newest = placeOrder(ALICE, now.minusSeconds(100));
        placeOrder(BOB, now);

        mockMvc.perform(MockMvcRequestBuilders.get("/orders?size=2").headers(TrustedHeaders.asCustomer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].orderId").value(newest.toString()))
                .andExpect(jsonPath("$.content[1].orderId").value(middle.toString()));
        mockMvc.perform(MockMvcRequestBuilders.get("/orders?size=2&page=1").headers(TrustedHeaders.asCustomer(ALICE)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(oldest.toString()));
        mockMvc.perform(MockMvcRequestBuilders.get("/orders?size=101").headers(TrustedHeaders.asCustomer(ALICE)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void details_ofAPaidOrder_includeDeliverysTtn() throws Exception {
        UUID orderId = placePaidOrder();
        wireMock.stubFor(get(urlPathEqualTo(SHIPMENTS)).willReturn(okJson("""
                [ {"orderId":"%s","status":"TTN_GENERATED","ttn":"20450012345678","failureReason":null} ]"""
                .formatted(orderId))));

        details(orderId, ALICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.items[0].productTitle").value("Phone"))
                .andExpect(jsonPath("$.ttn").value("20450012345678"))
                .andExpect(jsonPath("$.deliveryStatus").value("TTN_GENERATED"));
    }

    @Test
    void details_withDeliveryDown_stillShowTheOrder() throws Exception {
        UUID orderId = placePaidOrder();
        wireMock.stubFor(get(urlPathEqualTo(SHIPMENTS)).willReturn(serviceUnavailable()));

        details(orderId, ALICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.ttn").isEmpty())
                .andExpect(jsonPath("$.deliveryStatus").isEmpty());
    }

    @Test
    void details_ofAnUnpaidOrder_doNotAskDelivery() throws Exception {
        UUID orderId = placeOrder(ALICE, Instant.now());

        details(orderId, ALICE).andExpect(status().isOk()).andExpect(jsonPath("$.ttn").isEmpty());

        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(SHIPMENTS)));
    }

    @Test
    void details_ofSomeoneElsesOrder_is404() throws Exception {
        details(placeOrder(ALICE, Instant.now()), BOB).andExpect(status().isNotFound());
    }

    private ResultActions pay(UUID orderId, UUID customer) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/orders/" + orderId + "/pay")
                .headers(TrustedHeaders.asCustomer(customer))
                .contentType(MediaType.APPLICATION_JSON).content(CARD));
    }

    private ResultActions details(UUID orderId, UUID customer) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/orders/" + orderId)
                .headers(TrustedHeaders.asCustomer(customer)));
    }

    private UUID placePaidOrder() {
        OrderEntity order = order(ALICE, Instant.now());
        order.markPaid(Instant.now());
        return repository.saveAndFlush(order).getId();
    }

    private UUID placeOrder(UUID customer, Instant placedAt) {
        return repository.saveAndFlush(order(customer, placedAt)).getId();
    }

    private static OrderEntity order(UUID customer, Instant placedAt) {
        return OrderEntity.place(UUID.randomUUID(), customer, "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
                "UAH", placedAt, Duration.ofMinutes(15));
    }
}
