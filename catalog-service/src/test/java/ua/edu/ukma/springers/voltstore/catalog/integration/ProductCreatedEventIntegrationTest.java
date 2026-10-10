package ua.edu.ukma.springers.voltstore.catalog.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.messaging.ProductEventProducer;
import ua.edu.ukma.springers.voltstore.catalog.support.TopicProbe;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.productJson;

// Task 11: creating a product publishes ProductCreated, and the in-service listener receives it
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProductCreatedEventIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @BeforeEach
    void cleanTables() {
        clean(jdbc);
    }

    @Test
    void createProduct_publishesExactlyOneEventKeyedByProductId(CapturedOutput output) throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            UUID categoryId = createCategory("Laptops");
            String productId = createProduct(categoryId);

            // Exactly one event for this product arrives and no second one follows
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> recordsFor(probe.poll(), productId).size() == 1);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                    .until(() -> recordsFor(probe.poll(), productId).size() == 1);

            ConsumerRecord<String, String> record = recordsFor(probe.poll(), productId).getFirst();
            JsonNode event = JSON.readTree(record.value());
            assertThat(event.get("productId").asString()).isEqualTo(productId);
            assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
            // ISO-8601 UTC instant, not a number
            assertThat(Instant.parse(event.get("timestamp").asString())).isBeforeOrEqualTo(Instant.now());
            assertThat(new String(record.headers().lastHeader(ProductEventProducer.EVENT_TYPE_HEADER).value(),
                    StandardCharsets.UTF_8)).isEqualTo("ProductCreated");
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();

            // The in-service listener logged key, partition and offset of the same message
            String expectedLog = "Received ProductCreated: key=" + productId + ", partition=" + record.partition()
                    + ", offset=" + record.offset() + ", eventId=" + event.get("eventId").asString();
            await().atMost(Duration.ofSeconds(30)).until(() -> output.getAll().contains(expectedLog));
        }
    }

    @Test
    void differentProducts_spreadAcrossPartitions(CapturedOutput output) throws Exception {
        UUID categoryId = createCategory("Phones");
        for (int i = 0; i < 12; i++) {
            createProduct(categoryId);
        }

        // With 12 random keys the chance that all of them hash to the same one of 3 partitions is ~1e-5
        Pattern logLine = Pattern.compile("Received ProductCreated: key=\\S+, partition=(\\d+), offset=\\d+");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Matcher matcher = logLine.matcher(output.getAll());
            long distinctPartitions = matcher.results().map(m -> m.group(1)).distinct().count();
            assertThat(distinctPartitions).isGreaterThan(1);
        });
    }

    @Test
    void failedCreates_publishNoEvent() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            // 400: validation failure
            mockMvc.perform(post("/catalog/products").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"\",\"description\":\"\"}"))
                    .andExpect(status().isBadRequest());
            // 422: unknown category
            mockMvc.perform(post("/catalog/products").contentType(MediaType.APPLICATION_JSON)
                            .content(productJson(UUID.randomUUID())))
                    .andExpect(status().isUnprocessableContent());

            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> probe.poll().isEmpty());
        }
    }

    @Test
    void listener_toleratesUnknownPropertiesAndMissingTypeHeaders(CapturedOutput output) throws Exception {
        String productId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        // Written by a "foreign" producer: plain JSON string, no type headers, an extra field
        String json = "{\"eventId\":\"" + eventId + "\",\"productId\":\"" + productId
                + "\",\"timestamp\":\"2026-10-10T12:00:00Z\",\"newField\":42}";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(KafkaTopics.PRODUCT_EVENTS, productId, json)).get();
        }

        await().atMost(Duration.ofSeconds(30)).until(() ->
                output.getAll().contains("eventId=" + eventId + ", productId=" + productId));
    }

    private UUID createCategory(String name) throws Exception {
        String body = mockMvc.perform(post("/catalog/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(body).get("categoryId").asString());
    }

    private String createProduct(UUID categoryId) throws Exception {
        String body = mockMvc.perform(post("/catalog/products").contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(categoryId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("productId").asString();
    }

    private static List<ConsumerRecord<String, String>> recordsFor(List<ConsumerRecord<String, String>> records,
                                                                  String productId) {
        return records.stream().filter(record -> productId.equals(record.key())).toList();
    }
}
