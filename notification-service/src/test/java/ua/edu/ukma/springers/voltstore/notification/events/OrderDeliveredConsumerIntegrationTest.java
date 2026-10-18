package ua.edu.ukma.springers.voltstore.notification.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.notification.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.repositories.OrderRecipientRepository;
import ua.edu.ukma.springers.voltstore.notification.support.EventPayloads;
import ua.edu.ukma.springers.voltstore.notification.support.KafkaEvents;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Task 07 (EP-NOTIF-EVT-04). The test plays Delivery Service; the sender job is switched off, so the recorded
 * notifications stay {@code PENDING} without a recipient.
 */
@SpringBootTest(properties = "notification.sender.enabled=false")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, KafkaEvents.SourceTopics.class})
class OrderDeliveredConsumerIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationRepository notifications;

    // Only observed: the handler must not touch the read model
    @MockitoSpyBean
    private OrderRecipientRepository recipients;

    private KafkaEvents events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM order_recipients");
        events = new KafkaEvents(kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    void orderDelivered_ofAnOrderNeverSeen_isRecordedWithoutRecipient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendDeliveryEvent(orderId, "OrderDelivered", EventPayloads.orderDelivered(eventId, orderId));

        NotificationEntity notification = awaitNotification(eventId);
        assertThat(notification.getOrderId()).isEqualTo(orderId);
        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_DELIVERED);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getRecipient()).isNull();
        assertThat(notification.getSubject()).isEqualTo("VoltStore — Order #" + orderId + " delivered");
        assertThat(notification.getBody()).contains("20450000123456");
        verify(recipients, never()).upsertRecipient(any(), any(), any());
        verify(recipients, never()).findRecipient(any());
        assertThat(events.deadLetters(KafkaEvents.DELIVERY_DLT, orderId)).isEmpty();
    }

    @Test
    void duplicate_recordsOneNotification() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String event = EventPayloads.orderDelivered(eventId, orderId);

        events.sendDeliveryEvent(orderId, "OrderDelivered", event);
        events.sendDeliveryEvent(orderId, "OrderDelivered", event);
        UUID marker = sendMarkerAfter(orderId);

        awaitNotification(marker);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
    }

    @Test
    void ttnGenerated_recordsNothing() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID ttnEvent = UUID.randomUUID();

        events.sendDeliveryEvent(orderId, "TTNGenerated", EventPayloads.ttnGenerated(ttnEvent, orderId));
        UUID marker = sendMarkerAfter(orderId);

        awaitNotification(marker);
        assertThat(notifications.findByEventId(ttnEvent)).isEmpty();
    }

    @Test
    void poisonRecords_goToTheDeliveryDeadLetterTopic() throws Exception {
        UUID key = UUID.randomUUID();
        String withoutTtn = EventPayloads.orderDelivered(UUID.randomUUID(), key)
                .replace("\"ttn\":\"20450000123456\",", "");

        events.sendDeliveryEvent(key, "OrderDelivered", "this is not JSON");
        events.sendDeliveryEvent(key, "OrderDelivered", withoutTtn);
        UUID marker = sendMarkerAfter(key);

        awaitNotification(marker);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE order_id = ?", Integer.class, key))
                .isEqualTo(1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(events.deadLetters(KafkaEvents.DELIVERY_DLT, key))
                .containsExactly("this is not JSON", withoutTtn));
        assertThat(events.deadLetters(KafkaEvents.ORDER_DLT, key)).isEmpty();
    }

    // Same key → same partition: once the marker is recorded, every earlier record of that key was processed
    private UUID sendMarkerAfter(UUID key) throws Exception {
        UUID marker = UUID.randomUUID();
        events.sendDeliveryEvent(key, "OrderDelivered", EventPayloads.orderDelivered(marker, key));
        return marker;
    }

    private NotificationEntity awaitNotification(UUID eventId) {
        await().atMost(WAIT).until(() -> notifications.findByEventId(eventId).isPresent());
        return notifications.findByEventId(eventId).orElseThrow();
    }
}
