package ua.edu.ukma.springers.voltstore.notification.events;

import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.notification.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.DeliveryEventHandler;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.OrderEventHandler;
import ua.edu.ukma.springers.voltstore.notification.support.KafkaEvents;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 04: the listeners' dispatch and error handling, with stub handlers instead of the real ones (the type names
 * {@code ProbeOrder}, {@code ProbeDelivery} and {@code FlakyOrder} exist only here).
 */
@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, KafkaEvents.SourceTopics.class,
        EventListenersIntegrationTest.StubHandlers.class})
class EventListenersIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    record ProbeEvent(@NotNull UUID eventId, @NotNull UUID orderId) {
    }

    abstract static class RecordingHandler implements EventHandler<ProbeEvent> {
        private final String eventType;
        final List<UUID> orderIds = new CopyOnWriteArrayList<>();

        RecordingHandler(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public Class<ProbeEvent> payloadType() {
            return ProbeEvent.class;
        }

        @Override
        public void handle(ProbeEvent event) {
            orderIds.add(event.orderId());
        }
    }

    static class OrderProbe extends RecordingHandler implements OrderEventHandler<ProbeEvent> {
        OrderProbe(String eventType) {
            super(eventType);
        }
    }

    static class DeliveryProbe extends RecordingHandler implements DeliveryEventHandler<ProbeEvent> {
        DeliveryProbe(String eventType) {
            super(eventType);
        }
    }

    // Fails the first two deliveries of every order, like a database that is briefly down
    static class FlakyOrderProbe extends OrderProbe {
        final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

        FlakyOrderProbe() {
            super("FlakyOrder");
        }

        @Override
        public void handle(ProbeEvent event) {
            if (calls.computeIfAbsent(event.orderId(), id -> new AtomicInteger()).incrementAndGet() <= 2) {
                throw new TransientDataAccessResourceException("database restarting");
            }
            super.handle(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubHandlers {
        @Bean
        OrderProbe probeOrderHandler() {
            return new OrderProbe("ProbeOrder");
        }

        @Bean
        FlakyOrderProbe flakyOrderHandler() {
            return new FlakyOrderProbe();
        }

        @Bean
        DeliveryProbe probeDeliveryHandler() {
            return new DeliveryProbe("ProbeDelivery");
        }
    }

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private OrderProbe probeOrderHandler;

    @Autowired
    private FlakyOrderProbe flakyOrderHandler;

    @Autowired
    private DeliveryProbe probeDeliveryHandler;

    private KafkaEvents events;

    @BeforeEach
    void setUp() {
        events = new KafkaEvents(kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    void eachListener_handlesOnlyItsOwnTopicsTypes() throws Exception {
        UUID onOrderTopic = UUID.randomUUID();
        UUID onDeliveryTopic = UUID.randomUUID();
        UUID deliveryTypeOnOrderTopic = UUID.randomUUID();
        UUID orderTypeOnDeliveryTopic = UUID.randomUUID();

        events.sendOrderEvent(onOrderTopic, "ProbeOrder", probe(onOrderTopic));
        events.sendDeliveryEvent(onDeliveryTopic, "ProbeDelivery", probe(onDeliveryTopic));
        events.sendOrderEvent(deliveryTypeOnOrderTopic, "ProbeDelivery", probe(deliveryTypeOnOrderTopic));
        events.sendDeliveryEvent(orderTypeOnDeliveryTopic, "ProbeOrder", probe(orderTypeOnDeliveryTopic));
        // Markers on the same keys (same partitions) after the records that must be skipped
        events.sendOrderEvent(deliveryTypeOnOrderTopic, "ProbeOrder", probe(deliveryTypeOnOrderTopic));
        events.sendDeliveryEvent(orderTypeOnDeliveryTopic, "ProbeDelivery", probe(orderTypeOnDeliveryTopic));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(orderIds(probeOrderHandler)).contains(onOrderTopic, deliveryTypeOnOrderTopic);
            assertThat(orderIds(probeDeliveryHandler)).contains(onDeliveryTopic, orderTypeOnDeliveryTopic);
        });
        assertThat(orderIds(probeOrderHandler)).doesNotContain(onDeliveryTopic)
                .containsOnlyOnce(deliveryTypeOnOrderTopic);
        assertThat(orderIds(probeDeliveryHandler)).doesNotContain(onOrderTopic)
                .containsOnlyOnce(orderTypeOnDeliveryTopic);
    }

    @Test
    void unknownTypeAndMissingHeader_areSkipped() throws Exception {
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderRefunded", probe(orderId));
        events.sendOrderEvent(orderId, null, probe(orderId));
        events.sendDeliveryEvent(orderId, "TTNGenerated", probe(orderId));
        events.sendDeliveryEvent(orderId, null, probe(orderId));
        events.sendOrderEvent(orderId, "ProbeOrder", probe(orderId));
        events.sendDeliveryEvent(orderId, "ProbeDelivery", probe(orderId));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(orderIds(probeOrderHandler)).containsOnlyOnce(orderId);
            assertThat(orderIds(probeDeliveryHandler)).containsOnlyOnce(orderId);
        });
        assertThat(events.deadLetters(KafkaEvents.ORDER_DLT, orderId)).isEmpty();
        assertThat(events.deadLetters(KafkaEvents.DELIVERY_DLT, orderId)).isEmpty();
    }

    @Test
    void poisonRecords_goToTheirTopicsDeadLetterTopicAtOnce_andDoNotBlockLaterRecords() throws Exception {
        UUID key = UUID.randomUUID();
        String withoutEventId = "{\"orderId\":\"" + key + "\"}";
        String badUuid = "{\"eventId\":\"not-a-uuid\",\"orderId\":\"" + key + "\"}";

        long start = System.nanoTime();
        events.sendOrderEvent(key, "ProbeOrder", "this is not JSON");
        events.sendOrderEvent(key, "ProbeOrder", withoutEventId);
        events.sendDeliveryEvent(key, "ProbeDelivery", badUuid);
        events.sendOrderEvent(key, "ProbeOrder", probe(key));
        events.sendDeliveryEvent(key, "ProbeDelivery", probe(key));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(orderIds(probeOrderHandler)).contains(key);
            assertThat(orderIds(probeDeliveryHandler)).contains(key);
        });
        // No back-off retries (4 retries would take 15 s): poison records are not retried
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(events.deadLetters(KafkaEvents.ORDER_DLT, key))
                    .containsExactly("this is not JSON", withoutEventId);
            assertThat(events.deadLetters(KafkaEvents.DELIVERY_DLT, key)).containsExactly(badUuid);
        });
    }

    @Test
    void transientFailure_isRetriedAndNotDeadLettered() throws Exception {
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "FlakyOrder", probe(orderId));

        await().atMost(WAIT).untilAsserted(() -> assertThat(orderIds(flakyOrderHandler)).containsExactly(orderId));
        assertThat(flakyOrderHandler.calls.get(orderId)).hasValue(3);
        assertThat(events.deadLetters(KafkaEvents.ORDER_DLT, orderId)).isEmpty();
    }

    private static List<UUID> orderIds(RecordingHandler handler) {
        return handler.orderIds;
    }

    private static String probe(UUID orderId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + orderId + "\",\"unknown\":true}";
    }
}
