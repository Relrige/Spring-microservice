package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductCreatedFilter;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * EP-INV-EVT-01 against a real broker. Records are sent as raw JSON text (as Catalog sends them), with the
 * product UUID as key. Kafka keeps order only within a partition, so "is the earlier record processed?" is
 * answered by sending a marker record with the same key afterwards and waiting for the marker.
 */
@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class,
        ProductCreatedConsumerIntegrationTest.CatalogTopic.class})
class ProductCreatedConsumerIntegrationTest {
    private static final String TOPIC = "catalog.product-events";
    private static final Duration FIRST_DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Catalog owns the topic and broker auto-creation is disabled, so the test declares it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class CatalogTopic {
        @Bean
        NewTopic catalogProductEventsTopic() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private StockItemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private StockService stockService;

    private DefaultKafkaProducerFactory<String, String> producerFactory;
    private KafkaTemplate<String, String> rawTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM stock_item_reservations");
        jdbcTemplate.update("DELETE FROM stock_items");
        // The application's template serializes values as JSON, which would quote a raw String payload
        producerFactory = new DefaultKafkaProducerFactory<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
        rawTemplate = new KafkaTemplate<>(producerFactory);
    }

    @AfterEach
    void tearDown() {
        producerFactory.destroy();
    }

    @Test
    void newProduct_createsEmptyStockItem_andIgnoresUnknownProperties() throws Exception {
        UUID productId = UUID.randomUUID();

        send(productId.toString(), "ProductCreated",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + productId
                        + "\",\"timestamp\":\"2026-10-12T10:00:00Z\",\"name\":\"added by a newer Catalog\"}");

        await().atMost(FIRST_DELIVERY_TIMEOUT).until(() -> repository.existsById(productId));
        StockItemEntity item = repository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isZero();
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.getGrossWeightGrams()).isNull();
        assertThat(item.getLengthCm()).isNull();
        assertThat(item.getWidthCm()).isNull();
        assertThat(item.getHeightCm()).isNull();
    }

    @Test
    void sameEventTwice_createsOneStockItem_andNeverFails() throws Exception {
        UUID productId = UUID.randomUUID();
        String event = productCreated(productId);

        send(productId.toString(), "ProductCreated", event);
        send(productId.toString(), "ProductCreated", event);
        awaitMarkerAfter(productId.toString());

        assertThat(repository.count()).isEqualTo(2);   // the product and the marker
        assertThat(repository.existsById(productId)).isTrue();
        // Exactly two calls: a failure would have been retried and called the service again
        verify(stockService, times(2)).ensureStockItem(productId);
    }

    @Test
    void eventForExistingStockItem_changesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, 7);
        stockService.setPackaging(productId, 1200, 30, 20, 10);

        send(productId.toString(), "ProductCreated", productCreated(productId));
        awaitMarkerAfter(productId.toString());

        StockItemEntity item = repository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(7);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.getGrossWeightGrams()).isEqualTo(1200);
        assertThat(item.getLengthCm()).isEqualTo(30);
        assertThat(item.getWidthCm()).isEqualTo(20);
        assertThat(item.getHeightCm()).isEqualTo(10);
    }

    @Test
    void poisonMessages_areSkippedWithoutRetries_andLaterMessagesAreProcessed() throws Exception {
        // Warm-up: wait until the listener has its partitions, so the timing below measures only processing
        UUID warmUp = UUID.randomUUID();
        send(warmUp.toString(), "ProductCreated", productCreated(warmUp));
        await().atMost(FIRST_DELIVERY_TIMEOUT).until(() -> repository.existsById(warmUp));

        String key = UUID.randomUUID().toString();   // one key: all records below land in one partition, in order
        send(key, "ProductCreated", "{not json");
        send(key, "ProductCreated", "{\"eventId\":\"" + UUID.randomUUID() + "\"}");   // no productId
        send(key, "ProductUpdated", "{\"something\":\"else\"}");   // other event type: filtered out
        send(key, null, productCreated(UUID.randomUUID()));          // no eventType header: filtered out
        UUID afterPoison = UUID.randomUUID();
        send(key, "ProductCreated", productCreated(afterPoison));

        // Retrying a conversion failure would hold the partition for at least 1 + 2 + 4 s per poison message
        await().atMost(Duration.ofSeconds(5)).until(() -> repository.existsById(afterPoison));
        assertThat(repository.count()).isEqualTo(2);   // warm-up and afterPoison only
    }

    /**
     * Sends a ProductCreated for a new product with the given key and waits until it is processed; every
     * earlier record with the same key has been processed by then.
     */
    private void awaitMarkerAfter(String key) throws Exception {
        UUID marker = UUID.randomUUID();
        send(key, "ProductCreated", productCreated(marker));
        await().atMost(FIRST_DELIVERY_TIMEOUT).until(() -> repository.existsById(marker));
    }

    private void send(String key, String eventType, String json) throws Exception {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(TOPIC, key, json);
        if (eventType != null) {
            producerRecord.headers().add(ProductCreatedFilter.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        }
        rawTemplate.send(producerRecord).get(30, TimeUnit.SECONDS);
    }

    private static String productCreated(UUID productId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + productId
                + "\",\"timestamp\":\"2026-10-12T10:00:00Z\"}";
    }
}
