package ua.edu.ukma.springers.voltstore.order.outbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import ua.edu.ukma.springers.voltstore.order.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.events.OrderCancelledEvent;
import ua.edu.ukma.springers.voltstore.order.events.OrderEvent;
import ua.edu.ukma.springers.voltstore.order.events.OrderPaidEvent;
import ua.edu.ukma.springers.voltstore.order.events.OrderPlacedEvent;
import ua.edu.ukma.springers.voltstore.order.events.OrderShippedEvent;
import ua.edu.ukma.springers.voltstore.order.support.TopicProbe;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;

/**
 * Task 06. The scheduled relay is effectively switched off (it runs once at startup, then every hour) and the tests
 * run it by hand, so each step is deterministic. A broker outage is simulated by making the KafkaTemplate's send
 * fail, which is what the relay sees when the broker is unreachable.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {"order.outbox.relay-interval=1h", "order.outbox.publish-timeout=5s"})
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class})
class OutboxIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${order.kafka.topics.order-events}")
    private String topic;

    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void theTopicIsDeclaredByOrder_withThreePartitions() {
        try (TopicProbe probe = probe()) {
            assertThat(probe.partitionCount()).isEqualTo(3);
        }
    }

    @Test
    void aCommittedEvent_isPublishedWithKeyHeadersAndTheStoredPayload() {
        try (TopicProbe probe = probe()) {
            OrderEntity order = order();
            OrderPlacedEvent event = OrderPlacedEvent.of(order, Instant.now());

            tx.executeWithoutResult(status -> outbox.append(event));
            relay.publishDueEvents();

            ConsumerRecord<String, String> record = awaitRecords(probe, 1).getFirst();
            assertThat(record.key()).isEqualTo(order.getId().toString());
            assertThat(header(record, "eventType")).isEqualTo("OrderPlaced");
            assertThat(header(record, "eventId")).isEqualTo(event.eventId().toString());
            JsonNode payload = JSON.readTree(record.value());
            assertThat(payload.get("eventId").asString()).isEqualTo(event.eventId().toString());
            assertThat(payload.get("customerEmail").asString()).isEqualTo("customer@example.com");
            assertThat(payload.has("eventType")).isFalse();
            assertThat(record.headers().lastHeader("__TypeId__")).as("no Java type headers").isNull();
            assertThat(unpublished()).isZero();
        }
    }

    @Test
    void aRolledBackTransaction_leavesNoEvent() {
        tx.executeWithoutResult(status -> {
            outbox.append(OrderPlacedEvent.of(order(), Instant.now()));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void appendingOutsideATransaction_isRefused() {
        assertThatThrownBy(() -> outbox.append(OrderPlacedEvent.of(order(), Instant.now())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theEventsOfOneOrder_arePublishedInTheOrderTheyWereWritten() {
        try (TopicProbe probe = probe()) {
            OrderEntity order = order();
            order.markPaid(Instant.now());
            for (OrderEvent event : List.of(OrderPlacedEvent.of(order, Instant.now()),
                    OrderPaidEvent.of(order, Instant.now()), OrderShippedEvent.of(order, "20450012345678",
                            Instant.now()))) {
                tx.executeWithoutResult(status -> outbox.append(event));
            }

            relay.publishDueEvents();

            assertThat(awaitRecords(probe, 3)).extracting(record -> header(record, "eventType"))
                    .containsExactly("OrderPlaced", "OrderPaid", "OrderShipped");
        }
    }

    @Test
    void aBrokerOutage_delaysEventsButLosesNone_andHoldsBackOnlyTheSameOrder() {
        try (TopicProbe probe = probe()) {
            OrderEntity failing = order();
            OrderEntity other = order();
            tx.executeWithoutResult(status -> outbox.append(OrderPlacedEvent.of(failing, Instant.now())));
            tx.executeWithoutResult(status -> outbox.append(
                    OrderCancelledEvent.of(failing, OrderCancelledEvent.Reason.EXPIRED, Instant.now())));
            tx.executeWithoutResult(status -> outbox.append(OrderPlacedEvent.of(other, Instant.now())));

            // The first send fails, as with an unreachable broker; the run stops there
            doReturn(CompletableFuture.failedFuture(new KafkaException("broker down")))
                    .doCallRealMethod()
                    .when(kafkaTemplate).send(any(ProducerRecord.class));
            relay.publishDueEvents();
            assertThat(unpublished()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE aggregate_id = ? "
                    + "ORDER BY sequence_number LIMIT 1", Integer.class, failing.getId())).isEqualTo(1);

            // Next run: the failed event waits for its back-off and holds back the later event of the same
            // order; the other order's event goes out
            relay.publishDueEvents();
            List<ConsumerRecord<String, String>> afterOutage = awaitRecords(probe, 1);
            assertThat(afterOutage).extracting(ConsumerRecord::key).containsExactly(other.getId().toString());

            // Once the back-off is over, both events of the failing order go out, in order
            jdbc.update("UPDATE outbox_events SET next_attempt_at = ? WHERE published_at IS NULL",
                    Timestamp.from(Instant.now().minusSeconds(1)));
            relay.publishDueEvents();
            assertThat(awaitRecords(probe, 3).subList(1, 3)).extracting(record -> header(record, "eventType"))
                    .containsExactly("OrderPlaced", "OrderCancelled");
            assertThat(unpublished()).isZero();
        }
        doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void cleanup_deletesOnlyEventsPublishedLongerAgoThanTheRetention() {
        tx.executeWithoutResult(status -> outbox.append(OrderPlacedEvent.of(order(), Instant.now())));
        tx.executeWithoutResult(status -> outbox.append(OrderPlacedEvent.of(order(), Instant.now())));
        tx.executeWithoutResult(status -> outbox.append(OrderPlacedEvent.of(order(), Instant.now())));
        relay.publishDueEvents();
        await().atMost(Duration.ofSeconds(10)).until(() -> unpublished() == 0);
        jdbc.update("UPDATE outbox_events SET published_at = ? WHERE id IN "
                + "(SELECT id FROM outbox_events ORDER BY sequence_number LIMIT 2)",
                Timestamp.from(Instant.now().minus(Duration.ofDays(8))));

        assertThat(relay.deletePublishedEvents()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    private TopicProbe probe() {
        return new TopicProbe(kafka.getBootstrapServers(), topic);
    }

    private static List<ConsumerRecord<String, String>> awaitRecords(TopicProbe probe, int count) {
        return await().atMost(Duration.ofSeconds(20)).until(probe::poll, records -> records.size() >= count);
    }

    private int unpublished() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OrderEntity order() {
        return OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "customer@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("19999.50"), 2)),
                "UAH", Instant.now(), Duration.ofMinutes(15));
    }
}
