package ua.edu.ukma.springers.voltstore.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import ua.edu.ukma.springers.voltstore.order.jobs.OrphanReservationJob;
import ua.edu.ukma.springers.voltstore.order.support.TopicProbe;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tasks 07 and 08 end to end: the real security chain, controllers and services, PostgreSQL and Kafka in
 * Testcontainers, and WireMock in place of Catalog and Inventory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class})
class CheckoutIntegrationTest {
    private static final String CATALOG = "/catalog/products/batch-get";
    private static final String RESERVE = "/inventory/reservations";
    private static final String RELEASE = "/inventory/reservations/[0-9a-f-]+/release";
    private static final UUID PHONE = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DELIVERY = """
            {"name":"Ivan Petrenko","phone":"+380501234567","address":"Kyiv, branch 12","carrier":"NOVA_POSHTA"}""";

    static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        registry.add("clients.catalog.url", wireMock::baseUrl);
        registry.add("clients.inventory.url", wireMock::baseUrl);
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
    private KafkaContainer kafka;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private OrphanReservationJob orphanReservationJob;

    @Value("${order.kafka.topics.order-events}")
    private String topic;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        // Failures of one test must not open a breaker for the next
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM reservation_attempts");
    }

    // ---- EP-ORD-01 ----

    @Test
    void validate_activeAndInStock_is204() throws Exception {
        catalogAnswers(product(PHONE, "Phone", "19999.50", "ACTIVE", "IN_STOCK"));

        validate().andExpect(status().isNoContent());
    }

    @Test
    void validate_outOfStock_is422() throws Exception {
        catalogAnswers(product(PHONE, "Phone", "19999.50", "ACTIVE", "OUT_OF_STOCK"));

        validate()
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.ineligibleItems[0].productId").value(PHONE.toString()))
                .andExpect(jsonPath("$.ineligibleItems[0].reason").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void validate_catalogDown_is503() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo(CATALOG)).willReturn(serviceUnavailable()));

        validate().andExpect(status().isServiceUnavailable());
    }

    // ---- EP-ORD-02 ----

    @Test
    void place_storesTheOrderWithCatalogsPrices_andPublishesOrderPlaced() throws Exception {
        catalogAnswers(product(PHONE, "Phone", "19999.50", "ACTIVE", "IN_STOCK"),
                product(CASE, "Case", "499.00", "ACTIVE", "IN_STOCK"));
        wireMock.stubFor(post(urlEqualTo(RESERVE)).willReturn(noContent()));

        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), topic)) {
            // Prices, a total, a customer and an email in the body are ignored
            String body = """
                    {"customerId":"%s","customerEmail":"attacker@example.com","totalAmount":1,
                     "items":[{"productId":"%s","quantity":2,"unitPrice":1},{"productId":"%s","quantity":1}],
                     "deliveryDetails":%s}""".formatted(UUID.randomUUID(), PHONE, CASE, DELIVERY);

            String response = place(body)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.totalAmount").value(40498.00))
                    .andExpect(jsonPath("$.currency").value("UAH"))
                    .andExpect(header().exists("Location"))
                    .andReturn().getResponse().getContentAsString();
            UUID orderId = UUID.fromString(JSON.readTree(response).get("orderId").asString());

            Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", orderId);
            assertThat(order.get("customer_id")).isEqualTo(CUSTOMER);
            assertThat(order.get("customer_email")).isEqualTo(TrustedHeaders.EMAIL);
            assertThat(order.get("status")).isEqualTo("PENDING_PAYMENT");
            assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("40498.00");
            assertThat(order.get("delivery_phone")).isEqualTo("+380501234567");
            List<Map<String, Object>> items = jdbc.queryForList("SELECT product_title, unit_price_at_order, quantity "
                    + "FROM order_items WHERE order_id = ? ORDER BY product_title", orderId);
            assertThat(items).extracting(row -> row.get("product_title")).containsExactly("Case", "Phone");
            assertThat(items).extracting(row -> ((BigDecimal) row.get("unit_price_at_order")).toPlainString())
                    .containsExactly("499.00", "19999.50");

            // Inventory was asked to reserve under the same order ID
            wireMock.verify(postRequestedFor(urlEqualTo(RESERVE)).withRequestBody(equalToJson("""
                    {"orderId":"%s","items":[{"productId":"%s","quantity":2},{"productId":"%s","quantity":1}]}
                    """.formatted(orderId, PHONE, CASE))));

            List<ConsumerRecord<String, String>> records = await().atMost(Duration.ofSeconds(20))
                    .until(probe::poll, received -> !received.isEmpty());
            ConsumerRecord<String, String> record = records.getFirst();
            assertThat(record.key()).isEqualTo(orderId.toString());
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("OrderPlaced");
            JsonNode event = JSON.readTree(record.value());
            assertThat(event.get("customerEmail").asString()).isEqualTo(TrustedHeaders.EMAIL);
            assertThat(event.get("totalAmount").decimalValue()).isEqualByComparingTo("40498.00");
            // The reservation now belongs to a stored order; the attempt was deleted with it
            assertThat(count("reservation_attempts")).isZero();
        }
    }

    @Test
    void place_insufficientStock_is422_andStoresNothing() throws Exception {
        catalogAnswers(product(PHONE, "Phone", "19999.50", "ACTIVE", "IN_STOCK"));
        wireMock.stubFor(post(urlEqualTo(RESERVE)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json").withBody("""
                        {"status":422,"insufficientItems":[{"productId":"%s","requested":2,"available":1}]}
                        """.formatted(PHONE))));

        place(placeBody())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.insufficientItems[0].available").value(1));

        assertThat(count("orders")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void place_inactiveProduct_is422_andReservesNothing() throws Exception {
        catalogAnswers(product(PHONE, "Phone", "19999.50", "NOT_ACTIVE", "IN_STOCK"));

        place(placeBody())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.ineligibleItems[0].reason").value("NOT_ACTIVE"));

        wireMock.verify(0, postRequestedFor(urlEqualTo(RESERVE)));
        assertThat(count("orders")).isZero();
    }

    @Test
    void place_inventoryDown_is503_releasesTheReservation_andStoresNothing() throws Exception {
        catalogAnswers(product(PHONE, "Phone", "19999.50", "ACTIVE", "IN_STOCK"));
        wireMock.stubFor(post(urlEqualTo(RESERVE)).willReturn(serviceUnavailable()));
        wireMock.stubFor(post(urlMatching(RELEASE)).willReturn(noContent()));

        place(placeBody()).andExpect(status().isServiceUnavailable());

        wireMock.verify(postRequestedFor(urlMatching(RELEASE)));
        assertThat(count("orders")).isZero();
        assertThat(count("outbox_events")).isZero();
        // The attempt stays, in case Inventory reserves after the release
        assertThat(count("reservation_attempts")).isEqualTo(1);
    }

    @Test
    void anOrphanReservation_isReleasedByTheJob_onceItHasSettled() throws Exception {
        // Inventory reserves, but its answer never reaches Order (the call times out on every attempt), and the
        // immediate release fails too: before the fix the stock stayed reserved forever
        catalogAnswers(product(PHONE, "Phone", "19999.50", "ACTIVE", "IN_STOCK"));
        wireMock.stubFor(post(urlEqualTo(RESERVE)).willReturn(serviceUnavailable()));
        wireMock.stubFor(post(urlMatching(RELEASE)).willReturn(serviceUnavailable()));
        place(placeBody()).andExpect(status().isServiceUnavailable());
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        wireMock.resetRequests();
        wireMock.stubFor(post(urlMatching(RELEASE)).willReturn(noContent()));

        // Too young: a late reservation could still arrive, so the job leaves it alone
        orphanReservationJob.releaseOrphanReservations();
        wireMock.verify(0, postRequestedFor(urlMatching(RELEASE)));

        jdbc.update("UPDATE reservation_attempts SET created_at = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofMinutes(3))));
        orphanReservationJob.releaseOrphanReservations();

        wireMock.verify(1, postRequestedFor(urlMatching(RELEASE)));
        assertThat(count("reservation_attempts")).isZero();
    }

    private ResultActions validate() throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/orders/validate").headers(TrustedHeaders.asCustomer(CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":\"" + PHONE + "\",\"quantity\":1}]}"));
    }

    private ResultActions place(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/orders").headers(TrustedHeaders.asCustomer(CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String placeBody() {
        return "{\"items\":[{\"productId\":\"" + PHONE + "\",\"quantity\":2}],\"deliveryDetails\":" + DELIVERY + "}";
    }

    private void catalogAnswers(String... products) {
        wireMock.stubFor(get(urlPathEqualTo(CATALOG)).willReturn(okJson("[" + String.join(",", products) + "]")));
    }

    private static String product(UUID id, String title, String price, String status, String stockStatus) {
        return """
                {"productId":"%s","title":"%s","basePrice":%s,"status":"%s","stockStatus":"%s"}"""
                .formatted(id, title, price, status, stockStatus);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
