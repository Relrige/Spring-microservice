package ua.edu.ukma.springers.voltstore.catalog.integration;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.stubbing.Answer;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.repositories.ProductRepository;
import ua.edu.ukma.springers.voltstore.catalog.support.TopicProbe;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertCategory;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertProduct;

// Task 18: Inventory's stock events keep Product.stockStatus up to date. The test plays Inventory's part.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockEventsIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    // Lets a test make the database update fail once, like a short database outage
    @MockitoSpyBean
    private ProductRepository productRepository;

    private KafkaProducer<String, String> inventory;
    private UUID categoryId;

    @BeforeEach
    void setUp() {
        clean(jdbc);
        categoryId = insertCategory(jdbc, "Laptops");
        inventory = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterEach
    void closeProducer() {
        inventory.close();
    }

    @Test
    void replenishedThenDepleted_updatesStockStatusOfAnyProduct() throws Exception {
        // Not ACTIVE: stockStatus is tracked regardless of the product's status
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);

        // Unknown payload fields (here: availableQuantity) are ignored
        send(id.toString(), "ProductStockReplenished",
                "{\"productId\":\"" + id + "\",\"timestamp\":\"2026-10-11T10:00:00Z\",\"availableQuantity\":5}");
        await().atMost(WAIT).until(() -> stockStatus(id).equals("IN_STOCK"));

        send(id.toString(), "ProductStockDepleted", payload(id));
        await().atMost(WAIT).until(() -> stockStatus(id).equals("OUT_OF_STOCK"));

        Map<String, Object> row = jdbc.queryForMap("SELECT status, title FROM products WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("NOT_ACTIVE");
        assertThat(row.get("title")).isEqualTo("Laptop");
    }

    @Test
    void duplicateEvent_isIdempotent() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);

        send(id.toString(), "ProductStockReplenished", payload(id));
        send(id.toString(), "ProductStockReplenished", payload(id));
        UUID marker = markerAfter(id);

        await().atMost(WAIT).until(() -> stockStatus(marker).equals("IN_STOCK"));
        assertThat(stockStatus(id)).isEqualTo("IN_STOCK");
    }

    @Test
    void unknownProductAndUnknownEventType_areAcknowledgedAndSkipped() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);

        try (TopicProbe dlt = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.STOCK_EVENTS_DLT)) {
            UUID unknown = UUID.randomUUID();
            // Same key as the next message, so it is in the same partition and processed first
            send(id.toString(), "ProductStockReplenished", payload(unknown));
            send(id.toString(), "ProductPackagingChanged", payload(id));
            send(id.toString(), "ProductStockReplenished", payload(id));

            await().atMost(WAIT).until(() -> stockStatus(id).equals("IN_STOCK"));
            // Neither was retried until it went to the dead-letter topic
            assertThat(dlt.poll()).isEmpty();
        }
    }

    @Test
    void poisonMessages_goToTheDeadLetterTopicAndDoNotBlockLaterMessages() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);
        String key = id.toString();

        try (TopicProbe dlt = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.STOCK_EVENTS_DLT)) {
            send(key, "ProductStockReplenished", "this is not JSON");
            send(key, "ProductStockReplenished", "{\"timestamp\":\"2026-10-11T10:00:00Z\"}");
            send(key, "ProductStockReplenished", payload(id));

            await().atMost(WAIT).until(() -> stockStatus(id).equals("IN_STOCK"));
            await().atMost(WAIT).untilAsserted(() -> assertThat(dlt.poll())
                    .filteredOn(record -> key.equals(record.key()))
                    .extracting(record -> record.value())
                    .containsExactly("this is not JSON", "{\"timestamp\":\"2026-10-11T10:00:00Z\"}"));
        }
    }

    @Test
    void transientDatabaseFailure_isRetried() throws Exception {
        UUID id = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);
        // The repository is an interface proxy, so "call the real method" means the spy's delegating default answer
        Answer<?> realRepository = mockingDetails(productRepository).getMockCreationSettings().getDefaultAnswer();
        doThrow(new TransientDataAccessResourceException("database restarting"))
                .doAnswer(realRepository)
                .when(productRepository).updateStockStatus(eq(id), any());

        try (TopicProbe dlt = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.STOCK_EVENTS_DLT)) {
            send(id.toString(), "ProductStockReplenished", payload(id));

            await().atMost(WAIT).until(() -> stockStatus(id).equals("IN_STOCK"));
            verify(productRepository, atLeast(2)).updateStockStatus(eq(id), any());
            assertThat(dlt.poll()).isEmpty();
        }
    }

    // A second product updated by an event sent after the given product's events, on the same partition:
    // once it is applied, every earlier event of that partition has been processed too
    private UUID markerAfter(UUID id) throws Exception {
        UUID marker = insertProduct(jdbc, categoryId, ProductStatus.ACTIVE, StockStatus.OUT_OF_STOCK);
        send(id.toString(), "ProductStockReplenished", payload(marker));
        return marker;
    }

    private void send(String key, String eventType, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopics.STOCK_EVENTS, key, json);
        record.headers().add(KafkaTopics.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        inventory.send(record).get();
    }

    private static String payload(UUID productId) {
        return "{\"productId\":\"" + productId + "\",\"timestamp\":\"2026-10-11T10:00:00Z\"}";
    }

    private String stockStatus(UUID id) {
        return jdbc.queryForObject("SELECT stock_status FROM products WHERE id = ?", String.class, id);
    }
}
