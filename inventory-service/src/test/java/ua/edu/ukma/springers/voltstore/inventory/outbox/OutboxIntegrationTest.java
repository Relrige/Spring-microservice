package ua.edu.ukma.springers.voltstore.inventory.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockReplenishedEvent;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;
import ua.edu.ukma.springers.voltstore.inventory.support.TopicProbe;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 17. The scheduled relay is effectively switched off (it runs once at startup, then every hour), and the
 * tests run it by hand, so each step is deterministic. A broker outage is simulated by making the KafkaTemplate's
 * send fail, which is exactly what the relay sees when the broker is unreachable.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {"inventory.outbox.relay-interval=1h", "inventory.outbox.publish-timeout=5s"})
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class OutboxIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REPLENISHED = "ProductStockReplenished";
    private static final String DEPLETED = "ProductStockDepleted";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private OutboxProperties properties;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private OutboxStockEventPublisher publisher;

    @MockitoSpyBean
    private StockItemReservationRepository reservationRepository;

    // The @Primary JSON template the relay uses (a second one exists for @RetryableTopic forwarding)
    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM stock_item_reservations");
        jdbc.update("DELETE FROM stock_items");
    }

    @Test
    void restockFromZero_writesOneEventInTheTransaction_andTheRelayPublishesItWithTheSameWireFormat() throws Exception {
        try (TopicProbe probe = probe()) {
            UUID productId = UUID.randomUUID();

            adjust(productId, 10).andExpect(status().isOk());
            adjust(productId, 5).andExpect(status().isOk());   // 10 -> 15: no event

            List<Map<String, Object>> rows = rowsFor(productId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("event_type")).isEqualTo(REPLENISHED);
            assertThat(rows.getFirst().get("published_at")).isNull();

            relay.publishDueEvents();

            assertThat(rowsFor(productId).getFirst().get("published_at")).isNotNull();
            ConsumerRecord<String, String> record = awaitRecords(probe, productId, 1).getFirst();
            UUID eventId = (UUID) rows.getFirst().get("id");
            assertThat(record.key()).isEqualTo(productId.toString());
            assertThat(header(record, KafkaTopics.EVENT_TYPE_HEADER)).isEqualTo(REPLENISHED);
            assertThat(header(record, KafkaTopics.EVENT_ID_HEADER)).isEqualTo(eventId.toString());
            // A JSON object, not a JSON string holding the JSON text (the double-encoding trap)
            JsonNode value = JSON.readTree(record.value());
            assertThat(value.isObject()).isTrue();
            assertThat(value.get("eventId").asString()).isEqualTo(eventId.toString());
            assertThat(value.get("productId").asString()).isEqualTo(productId.toString());
            assertThat(Instant.parse(value.get("timestamp").asString())).isNotNull();
            assertThat(value.has("eventType")).isFalse();
        }
    }

    @Test
    void brokerDownDuringAdjust_returns200_andTheEventIsPublishedWhenTheBrokerIsBack() throws Exception {
        try (TopicProbe probe = probe()) {
            failAllSends();
            UUID productId = UUID.randomUUID();

            adjust(productId, 10).andExpect(status().isOk());
            relay.publishDueEvents();

            Map<String, Object> row = rowsFor(productId).getFirst();
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).contains("broker down");
            assertThat(relay.oldestUnpublishedAgeSeconds()).isPositive();

            // Broker back; skip the back-off wait instead of sleeping
            doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
            makeAllDue();
            relay.publishDueEvents();

            assertThat(rowsFor(productId).getFirst().get("published_at")).isNotNull();
            assertThat(awaitRecords(probe, productId, 1)).extracting(r -> header(r, KafkaTopics.EVENT_TYPE_HEADER))
                    .containsExactly(REPLENISHED);
            assertThat(relay.oldestUnpublishedAgeSeconds()).isZero();
        }
    }

    @Test
    void rejectedWriteOff_leavesNoOutboxRow() throws Exception {
        UUID productId = UUID.randomUUID();
        adjust(productId, 3).andExpect(status().isOk());

        adjust(productId, -4).andExpect(status().isUnprocessableContent());

        assertThat(rowsFor(productId)).extracting(row -> row.get("event_type")).containsExactly(REPLENISHED);
    }

    @Test
    void outboxWriteFails_rollsBackTheStockChange() throws Exception {
        // Stub the spy behind the transactional proxy: stubbing through the proxy would run its MANDATORY check
        OutboxStockEventPublisher spy = AopTestUtils.getUltimateTargetObject(publisher);
        doThrow(new IllegalStateException("outbox insert failed")).when(spy).publish(any());
        UUID productId = UUID.randomUUID();

        adjust(productId, 10).andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_items", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void reservationFailsAfterTheEventsWereWritten_rollsBackEventsAndStock() throws Exception {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        adjust(productA, 2).andExpect(status().isOk());
        adjust(productB, 1).andExpect(status().isOk());
        // Both products would be depleted (two Depleted rows written), then saving the reservations fails
        doThrow(new IllegalStateException("reservation insert failed")).when(reservationRepository).saveAll(anyIterable());

        reserve(UUID.randomUUID(), Map.of(productA, 2, productB, 1)).andExpect(status().isInternalServerError());

        assertThat(rowsFor(productA)).extracting(row -> row.get("event_type")).containsExactly(REPLENISHED);
        assertThat(rowsFor(productB)).extracting(row -> row.get("event_type")).containsExactly(REPLENISHED);
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM stock_items WHERE product_id = ?",
                Integer.class, productA)).isEqualTo(2);
    }

    @Test
    void reserveThatDepletesSeveralProducts_writesOneEventPerProductInOneTransaction() throws Exception {
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();
        UUID productC = UUID.randomUUID();
        adjust(productA, 2).andExpect(status().isOk());
        adjust(productB, 1).andExpect(status().isOk());
        adjust(productC, 5).andExpect(status().isOk());

        reserve(UUID.randomUUID(), Map.of(productA, 2, productB, 1, productC, 1)).andExpect(status().isNoContent());

        assertThat(rowsFor(productA)).extracting(row -> row.get("event_type")).containsExactly(REPLENISHED, DEPLETED);
        assertThat(rowsFor(productB)).extracting(row -> row.get("event_type")).containsExactly(REPLENISHED, DEPLETED);
        // 5 -> 4 crosses no boundary
        assertThat(rowsFor(productC)).extracting(row -> row.get("event_type")).containsExactly(REPLENISHED);
        // Same transaction: the two Depleted rows have neighbouring sequence numbers
        List<Long> depletedSequence = jdbc.queryForList(
                "SELECT sequence_number FROM outbox_events WHERE event_type = ? ORDER BY sequence_number",
                Long.class, DEPLETED);
        assertThat(depletedSequence).hasSize(2);
        assertThat(depletedSequence.get(1) - depletedSequence.get(0)).isEqualTo(1);
    }

    @Test
    void perProductOrder_isKeptWithTwoRelaysAndATransientFailureOnTheFirstEvent() throws Exception {
        try (TopicProbe probe = probe()) {
            UUID productId = UUID.randomUUID();
            UUID otherProduct = UUID.randomUUID();
            adjust(productId, 5).andExpect(status().isOk());   // Replenished (its first send fails)
            adjust(otherProduct, 1).andExpect(status().isOk());
            adjust(productId, -5).andExpect(status().isOk());  // Depleted
            reserve(UUID.randomUUID(), Map.of(otherProduct, 1)).andExpect(status().isNoContent());
            adjust(productId, 7).andExpect(status().isOk());   // Replenished
            List<UUID> expectedIds = rowsFor(productId).stream().map(row -> (UUID) row.get("id")).toList();

            AtomicBoolean failedOnce = new AtomicBoolean();
            doAnswer(invocation -> {
                ProducerRecord<String, Object> record = invocation.getArgument(0);
                boolean first = expectedIds.getFirst().toString().equals(header(record, KafkaTopics.EVENT_ID_HEADER));
                return first && failedOnce.compareAndSet(false, true)
                        ? CompletableFuture.failedFuture(new KafkaException("transient"))
                        : invocation.callRealMethod();
            }).when(kafkaTemplate).send(any(ProducerRecord.class));

            OutboxRelay secondReplica = new OutboxRelay(repository, kafkaTemplate, jsonMapper, properties,
                    transactionManager);
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                CompletableFuture<Void> a = CompletableFuture.runAsync(relay::publishDueEvents);
                CompletableFuture<Void> b = CompletableFuture.runAsync(secondReplica::publishDueEvents);
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
                return unpublishedCount() == 0;
            });

            assertThat(failedOnce).isTrue();
            List<ConsumerRecord<String, String>> records = awaitRecords(probe, productId, 3);
            assertThat(records).extracting(r -> header(r, KafkaTopics.EVENT_TYPE_HEADER))
                    .containsExactly(REPLENISHED, DEPLETED, REPLENISHED);
            assertThat(records).extracting(r -> UUID.fromString(header(r, KafkaTopics.EVENT_ID_HEADER)))
                    .containsExactlyElementsOf(expectedIds);
            assertThat(awaitRecords(probe, otherProduct, 2)).extracting(r -> header(r, KafkaTopics.EVENT_TYPE_HEADER))
                    .containsExactly(REPLENISHED, DEPLETED);
        }
    }

    @Test
    void failingEvent_holdsBackLaterEventsOfItsProductOnly() throws Exception {
        try (TopicProbe probe = probe()) {
            UUID failing = UUID.randomUUID();
            UUID healthy = UUID.randomUUID();
            adjust(failing, 5).andExpect(status().isOk());
            adjust(failing, -5).andExpect(status().isOk());
            adjust(healthy, 3).andExpect(status().isOk());
            doAnswer(invocation -> {
                ProducerRecord<String, Object> record = invocation.getArgument(0);
                return failing.toString().equals(record.key())
                        ? CompletableFuture.failedFuture(new KafkaException("rejected"))
                        : invocation.callRealMethod();
            }).when(kafkaTemplate).send(any(ProducerRecord.class));

            // Run 1 stops at the failing product's first event; run 2 skips that product and publishes the other
            relay.publishDueEvents();
            relay.publishDueEvents();

            assertThat(awaitRecords(probe, healthy, 1)).isNotEmpty();
            List<Map<String, Object>> failingRows = rowsFor(failing);
            assertThat(failingRows).allSatisfy(row -> assertThat(row.get("published_at")).isNull());
            assertThat(failingRows.get(0).get("attempts")).isEqualTo(1);
            // The later event was never even tried: it must not overtake the failing one
            assertThat(failingRows.get(1).get("attempts")).isEqualTo(0);
        }
    }

    @Test
    void whileAnotherReplicaHoldsTheRelayLock_aRunPublishesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        adjust(productId, 1).andExpect(status().isOk());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Boolean> otherReplica = CompletableFuture.supplyAsync(() ->
                transactionTemplate.execute(status -> {
                    boolean acquired = repository.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY);
                    locked.countDown();
                    awaitQuietly(release);
                    return acquired;
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        relay.publishDueEvents();
        assertThat(unpublishedCount()).isEqualTo(1);

        release.countDown();
        assertThat(otherReplica.get(10, TimeUnit.SECONDS)).isTrue();
        relay.publishDueEvents();
        assertThat(unpublishedCount()).isZero();
    }

    @Test
    void publish_outsideATransaction_isRejected() {
        assertThatThrownBy(() -> publisher.publish(ProductStockReplenishedEvent.forProduct(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void cleanup_deletesOnlyEventsPublishedLongerAgoThanTheRetention() throws Exception {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID unpublished = UUID.randomUUID();
        adjust(old, 1).andExpect(status().isOk());
        adjust(recent, 1).andExpect(status().isOk());
        adjust(unpublished, 1).andExpect(status().isOk());
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '8 days' WHERE aggregate_id = ?", old);
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '1 hour' WHERE aggregate_id = ?", recent);

        assertThat(relay.deletePublishedEvents()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_events", UUID.class))
                .containsExactlyInAnyOrder(recent, unpublished);
    }

    private ResultActions adjust(UUID productId, int delta) throws Exception {
        return mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + "}"));
    }

    private ResultActions reserve(UUID orderId, Map<UUID, Integer> quantities) throws Exception {
        String items = String.join(",", quantities.entrySet().stream()
                .map(e -> "{\"productId\":\"" + e.getKey() + "\",\"quantity\":" + e.getValue() + "}").toList());
        return mockMvc.perform(post("/inventory/reservations")
                .headers(TrustedHeaders.asInternalService())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"items\":[" + items + "]}"));
    }

    private List<Map<String, Object>> rowsFor(UUID productId) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY sequence_number",
                productId);
    }

    private int unpublishedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
    }

    // Due now by the JVM clock, which is the only clock the relay uses (a Docker VM clock can be off by hours)
    private void makeAllDue() {
        jdbc.update("UPDATE outbox_events SET next_attempt_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
    }

    private void failAllSends() {
        doAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("broker down")))
                .when(kafkaTemplate).send(any(ProducerRecord.class));
    }

    private TopicProbe probe() {
        return new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.STOCK_EVENTS);
    }

    private static List<ConsumerRecord<String, String>> awaitRecords(TopicProbe probe, UUID productId, int count) {
        return await().atMost(Duration.ofSeconds(30)).until(
                () -> probe.poll().stream().filter(r -> productId.toString().equals(r.key())).toList(),
                records -> records.size() >= count);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static String header(ProducerRecord<String, Object> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
