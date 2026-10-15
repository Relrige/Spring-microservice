package ua.edu.ukma.springers.voltstore.delivery.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.delivery.events.OrderDeliveredEvent;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEvent;
import ua.edu.ukma.springers.voltstore.delivery.events.ShipmentEventPublisher;
import ua.edu.ukma.springers.voltstore.delivery.events.TtnGeneratedEvent;
import ua.edu.ukma.springers.voltstore.delivery.support.TopicProbe;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * Task 06. Nothing in the service emits shipment events yet, so the tests append them through the publisher in a
 * transaction of their own, exactly as the later callers do. The scheduled relay is effectively switched off (it
 * runs once at startup, then every hour) and the tests run it by hand, so each step is deterministic. A broker
 * outage is simulated by making the KafkaTemplate's send fail, which is what the relay sees when the broker is
 * unreachable.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {"delivery.outbox.relay-interval=1h", "delivery.outbox.publish-timeout=5s"})
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class OutboxIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ShipmentEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void appendedEvent_isPublishedByTheRelayWithTheWireFormat() {
        try (TopicProbe probe = probe()) {
            UUID orderId = UUID.randomUUID();
            TtnGeneratedEvent event = TtnGeneratedEvent.of(orderId, "20450000000001", Instant.now());

            append(event);
            assertThat(rowsFor(orderId)).singleElement()
                    .satisfies(row -> assertThat(row.get("published_at")).isNull());

            relay.publishDueEvents();

            assertThat(rowsFor(orderId).getFirst().get("published_at")).isNotNull();
            ConsumerRecord<String, String> record = awaitRecords(probe, orderId, 1).getFirst();
            assertThat(record.key()).isEqualTo(orderId.toString());
            assertThat(header(record, KafkaTopics.EVENT_TYPE_HEADER)).isEqualTo("TTNGenerated");
            assertThat(header(record, KafkaTopics.EVENT_ID_HEADER)).isEqualTo(event.eventId().toString());
            // A JSON object with exactly the contract fields, not a JSON string holding the JSON text
            JsonNode value = JSON.readTree(record.value());
            assertThat(value.isObject()).isTrue();
            assertThat(value.propertyNames()).containsExactlyInAnyOrder("eventId", "orderId", "ttn", "timestamp");
            assertThat(value.get("eventId").asString()).isEqualTo(event.eventId().toString());
            assertThat(value.get("orderId").asString()).isEqualTo(orderId.toString());
            assertThat(value.get("ttn").asString()).isEqualTo("20450000000001");
            assertThat(Instant.parse(value.get("timestamp").asString())).isEqualTo(event.timestamp());
        }
    }

    @Test
    void brokerDown_eventStaysUnpublished_andIsPublishedWhenTheBrokerIsBack() {
        try (TopicProbe probe = probe()) {
            failSendsFor(null);
            UUID orderId = UUID.randomUUID();
            append(TtnGeneratedEvent.of(orderId, "20450000000002", Instant.now()));

            relay.publishDueEvents();

            Map<String, Object> row = rowsFor(orderId).getFirst();
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).contains("broker down");
            assertThat(relay.oldestUnpublishedAgeSeconds()).isPositive();

            // Broker back; skip the back-off wait instead of sleeping
            doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
            makeAllDue();
            relay.publishDueEvents();

            assertThat(rowsFor(orderId).getFirst().get("published_at")).isNotNull();
            assertThat(awaitRecords(probe, orderId, 1)).hasSize(1);
            assertThat(relay.oldestUnpublishedAgeSeconds()).isZero();
        }
    }

    @Test
    void rolledBackTransaction_leavesNoOutboxRow() {
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            publisher.publish(TtnGeneratedEvent.of(orderId, "20450000000003", Instant.now()));
            throw new IllegalStateException("status change failed after the event was appended");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rowsFor(orderId)).isEmpty();
    }

    @Test
    void failingEvent_holdsBackLaterEventsOfItsOrderOnly() {
        try (TopicProbe probe = probe()) {
            UUID failing = UUID.randomUUID();
            UUID healthy = UUID.randomUUID();
            append(TtnGeneratedEvent.of(failing, "20450000000004", Instant.now()));
            append(OrderDeliveredEvent.of(failing, "20450000000004", Instant.now()));
            append(TtnGeneratedEvent.of(healthy, "20450000000005", Instant.now()));
            failSendsFor(failing);

            // Run 1 stops at the failing order's first event; run 2 skips that order and publishes the other
            relay.publishDueEvents();
            relay.publishDueEvents();

            assertThat(awaitRecords(probe, healthy, 1)).isNotEmpty();
            List<Map<String, Object>> failingRows = rowsFor(failing);
            assertThat(failingRows).allSatisfy(row -> assertThat(row.get("published_at")).isNull());
            assertThat(failingRows.get(0).get("attempts")).isEqualTo(1);
            // OrderDelivered was never even tried: it must not overtake TTNGenerated
            assertThat(failingRows.get(1).get("attempts")).isEqualTo(0);

            doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
            makeAllDue();
            relay.publishDueEvents();
            assertThat(awaitRecords(probe, failing, 2)).extracting(r -> header(r, KafkaTopics.EVENT_TYPE_HEADER))
                    .containsExactly("TTNGenerated", "OrderDelivered");
        }
    }

    @Test
    void publish_outsideATransaction_isRejected() {
        assertThatThrownBy(() -> publisher.publish(TtnGeneratedEvent.of(UUID.randomUUID(), "1", Instant.now())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void cleanup_deletesOnlyEventsPublishedLongerAgoThanTheRetention() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID unpublished = UUID.randomUUID();
        append(TtnGeneratedEvent.of(old, "1", Instant.now()));
        append(TtnGeneratedEvent.of(recent, "2", Instant.now()));
        append(TtnGeneratedEvent.of(unpublished, "3", Instant.now()));
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '8 days' WHERE aggregate_id = ?", old);
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '1 hour' WHERE aggregate_id = ?", recent);

        assertThat(relay.deletePublishedEvents()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_events", UUID.class))
                .containsExactlyInAnyOrder(recent, unpublished);
    }

    private void append(ShipmentEvent event) {
        transactionTemplate.executeWithoutResult(status -> publisher.publish(event));
    }

    // null = every send fails (broker down); otherwise only the sends of that order
    private void failSendsFor(UUID orderId) {
        doAnswer(invocation -> {
            ProducerRecord<String, Object> record = invocation.getArgument(0);
            return orderId == null || orderId.toString().equals(record.key())
                    ? CompletableFuture.failedFuture(new KafkaException("broker down"))
                    : invocation.callRealMethod();
        }).when(kafkaTemplate).send(any(ProducerRecord.class));
    }

    private List<Map<String, Object>> rowsFor(UUID orderId) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY sequence_number",
                orderId);
    }

    // Due now by the JVM clock, which is the only clock the relay uses (a Docker VM clock can be off by hours)
    private void makeAllDue() {
        jdbc.update("UPDATE outbox_events SET next_attempt_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
    }

    private TopicProbe probe() {
        return new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.SHIPMENT_EVENTS);
    }

    private static List<ConsumerRecord<String, String>> awaitRecords(TopicProbe probe, UUID orderId, int count) {
        return await().atMost(Duration.ofSeconds(30)).until(
                () -> probe.poll().stream().filter(r -> orderId.toString().equals(r.key())).toList(),
                records -> records.size() >= count);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
