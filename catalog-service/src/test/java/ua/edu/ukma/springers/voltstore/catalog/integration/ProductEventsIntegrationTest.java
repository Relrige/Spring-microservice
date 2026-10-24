package ua.edu.ukma.springers.voltstore.catalog.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.support.TopicProbe;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.productJson;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asManager;

// Tasks 11, 19 and 29: product changes publish full-state ProductCreated / ProductUpdated through the outbox
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductEventsIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(30);

    // Inventory's readiness check, so activation can succeed
    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void inventoryClient(DynamicPropertyRegistry registry) {
        registry.add("clients.inventory.url", inventory::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @BeforeEach
    void cleanTables() {
        clean(jdbc);
        inventory.stubFor(get(urlPathMatching("/inventory/stock/.*/readiness"))
                .willReturn(aResponse().withStatus(204)));
    }

    @Test
    void createProduct_publishesExactlyOneFullStateEventKeyedByProductId() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            UUID categoryId = createCategory("Laptops");
            String productId = createProduct(categoryId);

            // Exactly one event for this product arrives and no second one follows
            await().atMost(WAIT).until(() -> recordsFor(probe.poll(), productId).size() == 1);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                    .until(() -> recordsFor(probe.poll(), productId).size() == 1);

            ConsumerRecord<String, String> record = recordsFor(probe.poll(), productId).getFirst();
            JsonNode event = JSON.readTree(record.value());
            String eventId = event.get("eventId").asString();
            assertThat(event.get("productId").asString()).isEqualTo(productId);
            assertThat(event.get("title").asString()).isEqualTo("Laptop");
            assertThat(event.get("categoryId").asString()).isEqualTo(categoryId.toString());
            assertThat(event.get("basePrice").decimalValue()).isEqualByComparingTo("999.99");
            assertThat(event.get("status").asString()).isEqualTo("NOT_ACTIVE");
            assertThat(event.get("version").asLong()).isZero();
            assertThat(event.has("description")).isFalse();
            assertThat(event.has("stockStatus")).isFalse();
            // ISO-8601 UTC instant, not a number
            assertThat(Instant.parse(event.get("timestamp").asString())).isBeforeOrEqualTo(Instant.now());
            assertThat(header(record, KafkaTopics.EVENT_TYPE_HEADER)).isEqualTo("ProductCreated");
            assertThat(header(record, KafkaTopics.EVENT_ID_HEADER)).isEqualTo(eventId);
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
            assertThat(record.partition()).isBetween(0, 2);
            assertThat(record.offset()).isNotNegative();

            // The outbox row is the event's record and is marked published
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                    "SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?::uuid", Boolean.class, eventId));
        }
    }

    @Test
    void updateAndStatusChanges_publishProductUpdatedWithIncreasingVersions() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            UUID categoryId = createCategory("Laptops");
            String productId = createProduct(categoryId);

            mockMvc.perform(put("/catalog/products/" + productId).headers(asManager())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Renamed\",\"description\":\"A laptop\",\"categoryId\":\""
                                    + categoryId + "\",\"basePrice\":1099.50}"))
                    .andExpect(status().isOk());
            changeStatus(productId, "ACTIVE");
            await().atMost(WAIT).until(() -> recordsFor(probe.poll(), productId).size() == 3);

            List<ConsumerRecord<String, String>> records = recordsFor(probe.poll(), productId);
            JsonNode updated = updatedEventWithStatus(records, "NOT_ACTIVE");
            assertThat(updated.get("title").asString()).isEqualTo("Renamed");
            assertThat(updated.get("basePrice").decimalValue()).isEqualByComparingTo("1099.50");
            assertThat(updated.get("version").asLong()).isEqualTo(1);
            JsonNode activated = updatedEventWithStatus(records, "ACTIVE");
            assertThat(activated.get("title").asString()).isEqualTo("Renamed");
            assertThat(activated.get("version").asLong()).isGreaterThan(1);
            for (ConsumerRecord<String, String> record : records) {
                assertThat(header(record, KafkaTopics.EVENT_ID_HEADER))
                        .isEqualTo(JSON.readTree(record.value()).get("eventId").asString());
            }
        }
    }

    @Test
    void statusRequestThatChangesNothing_publishesNoEvent() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            String productId = createProduct(createCategory("Phones"));
            await().atMost(WAIT).until(() -> recordsFor(probe.poll(), productId).size() == 1);

            // Deactivating a product that is already inactive
            changeStatus(productId, "NOT_ACTIVE");

            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> recordsFor(probe.poll(), productId).size() == 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'ProductUpdated'",
                    Integer.class)).isZero();
        }
    }

    @Test
    void differentProducts_spreadAcrossPartitions() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            UUID categoryId = createCategory("Phones");
            List<String> productIds = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                productIds.add(createProduct(categoryId));
            }

            // With 12 random keys the chance that all of them hash to the same one of 3 partitions is ~1e-5
            await().atMost(WAIT).until(() -> ours(probe.poll(), productIds).size() == 12);
            assertThat(ours(probe.poll(), productIds).stream().map(ConsumerRecord::partition).distinct().count())
                    .isGreaterThan(1);
        }
    }

    @Test
    void failedCreates_publishNoEvent() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            // 400: validation failure
            mockMvc.perform(post("/catalog/products").headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\",\"description\":\"\"}"))
                    .andExpect(status().isBadRequest());
            // 422: unknown category
            mockMvc.perform(post("/catalog/products").headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                            .content(productJson(UUID.randomUUID())))
                    .andExpect(status().isUnprocessableContent());

            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> probe.poll().isEmpty());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        }
    }

    private UUID createCategory(String name) throws Exception {
        String body = mockMvc.perform(post("/catalog/categories").headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("categoryId").asString());
    }

    private String createProduct(UUID categoryId) throws Exception {
        String body = mockMvc.perform(post("/catalog/products").headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(categoryId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("productId").asString();
    }

    private void changeStatus(String productId, String status) throws Exception {
        mockMvc.perform(patch("/catalog/products/" + productId + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    // Records may arrive in any order (the relay does not guarantee it), so events are found by content
    private static JsonNode updatedEventWithStatus(List<ConsumerRecord<String, String>> records, String status) {
        return records.stream()
                .filter(record -> "ProductUpdated".equals(header(record, KafkaTopics.EVENT_TYPE_HEADER)))
                .map(record -> JSON.readTree(record.value()))
                .filter(event -> status.equals(event.get("status").asString()))
                .findFirst()
                .orElseThrow();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static List<ConsumerRecord<String, String>> ours(List<ConsumerRecord<String, String>> records,
                                                            List<String> productIds) {
        return records.stream().filter(record -> productIds.contains(record.key())).toList();
    }

    private static List<ConsumerRecord<String, String>> recordsFor(List<ConsumerRecord<String, String>> records,
                                                                  String productId) {
        return records.stream().filter(record -> productId.equals(record.key())).toList();
    }
}
