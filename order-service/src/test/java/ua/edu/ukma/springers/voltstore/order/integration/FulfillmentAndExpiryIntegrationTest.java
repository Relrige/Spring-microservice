package ua.edu.ukma.springers.voltstore.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.jobs.PaymentWindowExpiryJob;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.support.TopicProbe;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
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
 * Tasks 13 and 14 end to end on PostgreSQL and Kafka, with WireMock in place of Inventory and Delivery. The expiry
 * job is run by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class})
class FulfillmentAndExpiryIntegrationTest {
    private static final String SHIPMENTS = "/delivery/shipments";
    private static final String TTN = "20450012345678";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        registry.add("clients.inventory.url", wireMock::baseUrl);
        registry.add("clients.delivery.url", wireMock::baseUrl);
        registry.add("resilience4j.retry.configs.default.waitDuration", () -> "10ms");
        registry.add("resilience4j.retry.configs.default.enableRandomizedWait", () -> "false");
        // Run by hand only
        registry.add("order.expiry.interval", () -> "1h");
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
    private PaymentWindowExpiryJob expiryJob;

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
    }

    // ---- EP-ORD-07 ----

    @Test
    void queue_ofMoreThan100Orders_asksDeliveryInChunks_andMergesTheAnswers() throws Exception {
        Instant start = Instant.now().minus(Duration.ofHours(3));
        List<UUID> ids = IntStream.range(0, 105)
                .mapToObj(i -> paidOrder(start.plusSeconds(i)))
                .toList();
        repository.saveAndFlush(order(Instant.now()));
        // Delivery knows only the first order's shipment
        wireMock.stubFor(WireMock.get(urlPathEqualTo(SHIPMENTS)).willReturn(okJson("""
                [ {"orderId":"%s","status":"TTN_GENERATED","ttn":"%s","failureReason":null} ]"""
                .formatted(ids.getFirst(), TTN))));

        mockMvc.perform(MockMvcRequestBuilders.get("/orders/fulfillment-queue")
                        .headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryDataAvailable").value(true))
                .andExpect(jsonPath("$.orders.length()").value(105))
                .andExpect(jsonPath("$.orders[0].orderId").value(ids.getFirst().toString()))
                .andExpect(jsonPath("$.orders[0].ttn").value(TTN))
                .andExpect(jsonPath("$.orders[104].orderId").value(ids.getLast().toString()))
                .andExpect(jsonPath("$.orders[104].deliveryStatus").isEmpty());

        wireMock.verify(2, getRequestedFor(urlPathEqualTo(SHIPMENTS)));
    }

    @Test
    void queue_withDeliveryDown_isStillShown() throws Exception {
        paidOrder(Instant.now());
        wireMock.stubFor(WireMock.get(urlPathEqualTo(SHIPMENTS)).willReturn(serviceUnavailable()));

        mockMvc.perform(MockMvcRequestBuilders.get("/orders/fulfillment-queue")
                        .headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryDataAvailable").value(false))
                .andExpect(jsonPath("$.orders.length()").value(1));
    }

    // ---- EP-ORD-08 ----

    @Test
    void ship_withATtn_marksShipped_andPublishesOrderShipped() throws Exception {
        UUID orderId = paidOrder(Instant.now());
        wireMock.stubFor(WireMock.get(urlPathEqualTo(SHIPMENTS)).willReturn(okJson("""
                [ {"orderId":"%s","status":"TTN_GENERATED","ttn":"%s","failureReason":null} ]"""
                .formatted(orderId, TTN))));

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), topic)) {
            mockMvc.perform(MockMvcRequestBuilders.post("/orders/" + orderId + "/ship")
                            .headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SHIPPED"))
                    .andExpect(jsonPath("$.ttn").value(TTN));

            assertThat(statusOf(orderId)).isEqualTo("SHIPPED");
            ConsumerRecord<String, String> record = await().atMost(Duration.ofSeconds(20))
                    .until(probe::poll, received -> !received.isEmpty()).getFirst();
            assertThat(record.key()).isEqualTo(orderId.toString());
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("OrderShipped");
            JsonNode event = JSON.readTree(record.value());
            assertThat(event.get("ttn").asString()).isEqualTo(TTN);
            assertThat(event.get("customerEmail").asString()).isEqualTo("customer@example.com");
        }
    }

    @Test
    void ship_whileTheTtnIsPending_is409() throws Exception {
        UUID orderId = paidOrder(Instant.now());
        wireMock.stubFor(WireMock.get(urlPathEqualTo(SHIPMENTS)).willReturn(okJson("""
                [ {"orderId":"%s","status":"TTN_PENDING","ttn":null,"failureReason":null} ]""".formatted(orderId))));

        mockMvc.perform(MockMvcRequestBuilders.post("/orders/" + orderId + "/ship")
                        .headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isConflict());
        assertThat(statusOf(orderId)).isEqualTo("PAID");
    }

    // ---- EP-ORD-JOB-01 ----

    @Test
    void expiry_releasesAndExpiresOnlyOrdersPastWindowAndGrace_andPublishesOrderCancelled() {
        wireMock.stubFor(WireMock.post(WireMock.urlMatching("/inventory/reservations/.+/release"))
                .willReturn(noContent()));
        Instant now = Instant.now();
        // Window ended 1 minute ago (past the 30 s grace), 10 seconds ago (inside it), and still open
        UUID expired = repository.saveAndFlush(order(now.minus(Duration.ofMinutes(16)))).getId();
        UUID inGrace = repository.saveAndFlush(order(now.minus(Duration.ofMinutes(15)).minusSeconds(10))).getId();
        UUID open = repository.saveAndFlush(order(now)).getId();

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), topic)) {
            expiryJob.expireDueOrders();

            assertThat(statusOf(expired)).isEqualTo("EXPIRED");
            assertThat(statusOf(inGrace)).isEqualTo("PENDING_PAYMENT");
            assertThat(statusOf(open)).isEqualTo("PENDING_PAYMENT");
            wireMock.verify(1, postRequestedFor(urlEqualTo("/inventory/reservations/" + expired + "/release")));
            wireMock.verify(1, postRequestedFor(WireMock.urlMatching("/inventory/reservations/.+/release")));

            ConsumerRecord<String, String> record = await().atMost(Duration.ofSeconds(20))
                    .until(probe::poll, received -> !received.isEmpty()).getFirst();
            assertThat(record.key()).isEqualTo(expired.toString());
            assertThat(JSON.readTree(record.value()).get("reason").asString()).isEqualTo("EXPIRED");
        }
    }

    @Test
    void expiry_withInventoryDown_keepsTheOrder_andTheNextRunFinishesIt() {
        UUID expired = repository.saveAndFlush(order(Instant.now().minus(Duration.ofMinutes(16)))).getId();
        wireMock.stubFor(WireMock.post(WireMock.urlMatching("/inventory/reservations/.+/release"))
                .willReturn(serviceUnavailable()));

        expiryJob.expireDueOrders();
        assertThat(statusOf(expired)).isEqualTo("PENDING_PAYMENT");

        wireMock.stubFor(WireMock.post(WireMock.urlMatching("/inventory/reservations/.+/release"))
                .willReturn(noContent()));
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        expiryJob.expireDueOrders();
        assertThat(statusOf(expired)).isEqualTo("EXPIRED");
    }

    private UUID paidOrder(Instant paidAt) {
        OrderEntity order = order(paidAt.minusSeconds(30));
        order.markPaid(paidAt);
        return repository.saveAndFlush(order).getId();
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private static OrderEntity order(Instant placedAt) {
        return OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 1)),
                "UAH", placedAt, Duration.ofMinutes(15));
    }
}

