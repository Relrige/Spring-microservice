package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.support.CatalogKnowsEveryProduct;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.idempotency.ProcessedEvents;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.Concurrently;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tasks 15 and 16: Inventory consumes Catalog's ProductCreated and Order's OrderShipped. The test plays the
 * producers' part with a plain Kafka producer, using the payloads from the Event Catalogue.
 */
@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, CatalogKnowsEveryProduct.class, ConsumedEventsIntegrationTest.SourceTopics.class})
class ConsumedEventsIntegrationTest {
    private static final String PRODUCT_EVENTS = "catalog.product-events";
    private static final String ORDER_EVENTS = "order.order-events";
    // Created by @RetryableTopic for the ProductCreated listener
    private static final String PRODUCT_EVENTS_RETRY_0 = "catalog.product-events-inventory-retry-0";
    private static final String PRODUCT_EVENTS_DLT = "catalog.product-events-inventory-dlt";
    private static final Duration WAIT = Duration.ofSeconds(30);

    // The source topics belong to Catalog and Order; the broker does not auto-create topics, so the test does
    @TestConfiguration(proxyBeanMethods = false)
    static class SourceTopics {
        @Bean
        NewTopic productEventsTopic() {
            return TopicBuilder.name(PRODUCT_EVENTS).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic orderEventsTopic() {
            return TopicBuilder.name(ORDER_EVENTS).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockService stockService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockItemReservationRepository reservationRepository;

    @Autowired
    private ProcessedEvents processedEvents;

    // Lets a test make the database fail once, like a short outage
    @MockitoSpyBean
    private StockItemRepository stockItemRepository;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM stock_item_reservations");
        jdbc.update("DELETE FROM stock_items");
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterEach
    void closeProducer() {
        producer.close();
    }

    // --- Task 15: ProductCreated ---

    @Test
    void productCreated_createsEmptyStockItem() throws Exception {
        UUID productId = UUID.randomUUID();

        // Unknown fields (here: title) are ignored
        send(PRODUCT_EVENTS, productId, "ProductCreated", "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"productId\":\"" + productId + "\",\"timestamp\":\"2026-10-12T10:00:00Z\",\"title\":\"x\"}");

        await().atMost(WAIT).until(() -> stockItemRepository.findById(productId).isPresent());
        StockItemEntity item = stockItemRepository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).isZero();
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.isPackagingConfigured()).isFalse();
    }

    @Test
    void productCreated_duplicateAndExistingStockItem_changeNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        // A worker was faster than the event
        stockService.adjustStock(productId, 7);

        send(PRODUCT_EVENTS, productId, "ProductCreated", productCreated(productId));
        send(PRODUCT_EVENTS, productId, "ProductCreated", productCreated(productId));
        UUID marker = sendMarkerAfter(productId);

        await().atMost(WAIT).until(() -> stockItemRepository.findById(marker).isPresent());
        assertThat(count("SELECT count(*) FROM stock_items WHERE product_id = ?", productId)).isEqualTo(1);
        assertThat(stockItemRepository.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(7);
    }

    @Test
    void productEventsTopic_otherEventTypesAreSkipped() throws Exception {
        UUID productId = UUID.randomUUID();

        send(PRODUCT_EVENTS, productId, "ProductUpdated", productCreated(productId));
        send(PRODUCT_EVENTS, productId, null, productCreated(productId));
        UUID marker = sendMarkerAfter(productId);

        await().atMost(WAIT).until(() -> stockItemRepository.findById(marker).isPresent());
        assertThat(stockItemRepository.findById(productId)).isEmpty();
    }

    @Test
    void poisonMessages_goToTheDeadLetterTopicAndDoNotBlockLaterMessages() throws Exception {
        UUID productId = UUID.randomUUID();
        String key = productId.toString();

        String withoutEventId = "{\"productId\":\"" + productId + "\",\"timestamp\":\"2026-10-12T10:00:00Z\"}";
        send(PRODUCT_EVENTS, productId, "ProductCreated", "this is not JSON");
        send(PRODUCT_EVENTS, productId, "ProductCreated", "{\"timestamp\":\"2026-10-12T10:00:00Z\"}");
        // Without an idempotency key the event cannot be deduplicated, so it is rejected as well
        send(PRODUCT_EVENTS, productId, "ProductCreated", withoutEventId);
        send(PRODUCT_EVENTS, productId, "ProductCreated", productCreated(productId));

        await().atMost(WAIT).until(() -> stockItemRepository.findById(productId).isPresent());
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(deadLetters(PRODUCT_EVENTS_DLT, key)).containsExactly(
                        "this is not JSON", "{\"timestamp\":\"2026-10-12T10:00:00Z\"}", withoutEventId));
        // Never retryable: they skipped the retry topics
        assertThat(deadLetters(PRODUCT_EVENTS_RETRY_0, key)).isEmpty();
    }

    @Test
    void sameEventDeliveredTwice_isAppliedOnce() throws Exception {
        UUID productId = UUID.randomUUID();
        String event = productCreated(productId);

        // e.g. the Catalog outbox re-sent it after a timed-out acknowledgment
        send(PRODUCT_EVENTS, productId, "ProductCreated", event);
        send(PRODUCT_EVENTS, productId, "ProductCreated", event);
        UUID marker = sendMarkerAfter(productId);

        await().atMost(WAIT).until(() -> stockItemRepository.findById(marker).isPresent());
        // The business change ran once; the duplicate stopped at the idempotency key
        verify(stockItemRepository, times(1)).insertIfAbsent(eq(productId));
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?::uuid", eventIdOf(event)))
                .isEqualTo(1);
    }

    @Test
    void transientDatabaseFailure_isRetried() throws Exception {
        UUID productId = UUID.randomUUID();
        // The repository is an interface proxy: "real method" = the spy's delegating default answer
        Answer<?> real = mockingDetails(stockItemRepository).getMockCreationSettings().getDefaultAnswer();
        doThrow(new TransientDataAccessResourceException("database restarting")).doAnswer(real)
                .when(stockItemRepository).insertIfAbsent(eq(productId));

        String event = productCreated(productId);
        send(PRODUCT_EVENTS, productId, "ProductCreated", event);

        await().atMost(WAIT).until(() -> stockItemRepository.findById(productId).isPresent());
        verify(stockItemRepository, atLeast(2)).insertIfAbsent(eq(productId));
        // Non-blocking retry: the failed record went through the first retry topic, not the DLT
        assertThat(deadLetters(PRODUCT_EVENTS_RETRY_0, productId.toString())).hasSize(1);
        assertThat(deadLetters(PRODUCT_EVENTS_DLT, productId.toString())).isEmpty();
        // The failed attempt rolled back its idempotency key together with the insert, so the retry was not
        // mistaken for a duplicate: exactly one key, and the StockItem exists
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?::uuid", eventIdOf(event)))
                .isEqualTo(1);
    }

    @Test
    void processedEventKeys_areDeletedAfterTheRetention() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_events VALUES (?, 'product-created', now() - interval '31 days')", old);
        jdbc.update("INSERT INTO processed_events VALUES (?, 'product-created', now() - interval '1 day')", recent);

        assertThat(processedEvents.deleteExpired()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT event_id FROM processed_events", UUID.class)).containsExactly(recent);
    }

    @Test
    void ensureStockItem_racingWithOnDemandCreation_endsWithOneRowAndTheAdjustedQuantity() throws Exception {
        UUID productId = UUID.randomUUID();

        List<Object> results = Concurrently.run(
                () -> stockService.ensureStockItem(productId),
                () -> stockService.adjustStock(productId, 5));

        assertThat(results).noneMatch(Exception.class::isInstance);
        assertThat(count("SELECT count(*) FROM stock_items WHERE product_id = ?", productId)).isEqualTo(1);
        assertThat(stockItemRepository.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(5);
    }

    // --- Task 16: OrderShipped ---

    @Test
    void orderShipped_consumesAllReservationsOfTheOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(10);
        UUID productB = stocked(3);
        reservationService.reserve(orderId, Map.of(productA, 4, productB, 3));

        // customerEmail is ignored
        send(ORDER_EVENTS, orderId, "OrderShipped",
                "{\"orderId\":\"" + orderId + "\",\"ttn\":\"NP-1\",\"customerEmail\":\"a@b.c\"}");

        await().atMost(WAIT).until(() ->
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.CONSUMED).size() == 2);
        assertStock(productA, 6, 0);
        assertStock(productB, 0, 0);
    }

    @Test
    void orderShipped_duplicateUnknownAndReleased_deductNothingTwice() throws Exception {
        UUID shipped = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        UUID productA = stocked(10);
        reservationService.reserve(shipped, Map.of(productA, 2));
        reservationService.reserve(released, Map.of(productA, 3));
        reservationService.release(released);

        send(ORDER_EVENTS, shipped, "OrderShipped", orderShipped(shipped));
        send(ORDER_EVENTS, shipped, "OrderShipped", orderShipped(shipped));
        send(ORDER_EVENTS, released, "OrderShipped", orderShipped(released));
        send(ORDER_EVENTS, UUID.randomUUID(), "OrderShipped", orderShipped(UUID.randomUUID()));
        // A cancellation on the same topic is not this listener's business
        send(ORDER_EVENTS, shipped, "OrderCancelled", orderShipped(shipped));

        await().atMost(WAIT).until(() ->
                reservationRepository.findByOrderIdAndStatus(shipped, ReservationStatus.CONSUMED).size() == 1);
        // Markers: for each key, a fresh order shipped after the records above (same key = same partition). Once
        // both markers are consumed, the duplicate and the released order's event have been processed too.
        List<UUID> markers = new ArrayList<>();
        for (UUID key : List.of(shipped, released)) {
            UUID marker = UUID.randomUUID();
            reservationService.reserve(marker, Map.of(productA, 1));
            send(ORDER_EVENTS, key, "OrderShipped", orderShipped(marker));
            markers.add(marker);
        }
        await().atMost(WAIT).until(() -> markers.stream().allMatch(marker ->
                reservationRepository.findByOrderIdAndStatus(marker, ReservationStatus.CONSUMED).size() == 1));

        // 10 stocked; 2 shipped once (not twice), 3 released back, 1 + 1 for the markers
        assertStock(productA, 10 - 2 - 1 - 1, 0);
        assertThat(reservationRepository.findByOrderIdAndStatus(released, ReservationStatus.RELEASED)).hasSize(1);
        assertThat(reservationRepository.findByOrderIdAndStatus(released, ReservationStatus.CONSUMED)).isEmpty();
    }

    @Test
    void consume_concurrentDuplicates_deductExactlyOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = stocked(5);
        reservationService.reserve(orderId, Map.of(productA, 5));

        List<Object> results = Concurrently.run(2, () -> reservationService.consume(orderId));

        assertThat(results).containsExactlyInAnyOrder(1, 0);
        assertStock(productA, 0, 0);
    }

    private UUID sendMarkerAfter(UUID key) throws Exception {
        // Same key → same partition: once the marker is applied, every earlier record of that key was processed
        UUID marker = UUID.randomUUID();
        send(PRODUCT_EVENTS, key, "ProductCreated", productCreated(marker));
        return marker;
    }

    private void send(String topic, UUID key, String eventType, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key.toString(), json);
        if (eventType != null) {
            record.headers().add(KafkaTopics.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        }
        producer.send(record).get();
    }

    private List<String> deadLetters(String topic, String key) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            List<String> values = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        values.add(record.value());
                    }
                }
            }
            return values;
        }
    }

    private UUID stocked(int quantity) {
        UUID productId = UUID.randomUUID();
        stockService.adjustStock(productId, quantity);
        return productId;
    }

    private void assertStock(UUID productId, int available, int reserved) {
        StockItemEntity item = stockItemRepository.findById(productId).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("available").isEqualTo(available);
        assertThat(item.getReservedQuantity()).as("reserved").isEqualTo(reserved);
    }

    private Integer count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private static String productCreated(UUID productId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + productId
                + "\",\"timestamp\":\"2026-10-12T10:00:00Z\"}";
    }

    private static String eventIdOf(String productCreatedJson) {
        return productCreatedJson.substring(productCreatedJson.indexOf(":\"") + 2, productCreatedJson.indexOf("\","));
    }

    private static String orderShipped(UUID orderId) {
        return "{\"orderId\":\"" + orderId + "\",\"ttn\":\"NP-1\"}";
    }
}
