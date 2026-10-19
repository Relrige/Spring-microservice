package ua.edu.ukma.springers.voltstore.notification.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

/**
 * Task 06 (EP-NOTIF-EVT-01/02/03/05). The test plays Order Service with a plain Kafka producer and the payloads from
 * the Event Catalogue. The sender job is switched off, so the recorded notifications stay {@code PENDING}.
 */
@SpringBootTest(properties = "notification.sender.enabled=false")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, KafkaEvents.SourceTopics.class})
class OrderEventsConsumerIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationRepository notifications;

    // Lets a test make the database fail once, like a short outage
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
    void orderPlaced_recordsAPendingNotificationAndTheRecipient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderPlaced", EventPayloads.orderPlaced(eventId, orderId));

        NotificationEntity notification = awaitNotification(eventId);
        assertPendingFor(notification, orderId, NotificationType.ORDER_PLACED);
        assertThat(notification.getSubject()).isEqualTo("VoltStore — Order #" + orderId + " placed, awaiting payment");
        assertThat(notification.getBody()).contains("1500.00 UAH").contains("15 October 2026, 15:15 (Europe/Kyiv)");
        assertThat(recipients.findRecipient(orderId)).contains(EventPayloads.EMAIL);
    }

    @Test
    void orderPaid_recordsAReceipt_andNoDeliveryDetails() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderPaid", EventPayloads.orderPaid(eventId, orderId));

        NotificationEntity notification = awaitNotification(eventId);
        assertPendingFor(notification, orderId, NotificationType.PAYMENT_RECEIVED);
        assertThat(notification.getSubject()).isEqualTo("VoltStore — Payment received for order #" + orderId);
        assertThat(notification.getBody()).contains("Amount paid: 1500.00 UAH")
                .doesNotContain("Ivan Petrenko", "+380501234567", "Kyiv, branch 12");
        assertThat(recipients.findRecipient(orderId)).contains(EventPayloads.EMAIL);
    }

    @Test
    void orderShipped_recordsTheTrackingEmail() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderShipped", EventPayloads.orderShipped(eventId, orderId));

        NotificationEntity notification = awaitNotification(eventId);
        assertPendingFor(notification, orderId, NotificationType.ORDER_SHIPPED);
        assertThat(notification.getBody()).contains("20450000123456");
        assertThat(recipients.findRecipient(orderId)).contains(EventPayloads.EMAIL);
    }

    @Test
    void orderCancelled_byTheCustomer_recordsTheCancellationEmail() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderCancelled",
                EventPayloads.orderCancelled(eventId, orderId, "CUSTOMER_CANCELLED"));

        NotificationEntity notification = awaitNotification(eventId);
        assertPendingFor(notification, orderId, NotificationType.ORDER_CANCELLED);
        assertThat(notification.getBody()).contains("If the order was already paid");
        assertThat(recipients.findRecipient(orderId)).contains(EventPayloads.EMAIL);
    }

    @Test
    void orderCancelled_expired_recordsTheExpiryEmail() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderCancelled", EventPayloads.orderCancelled(eventId, orderId, "EXPIRED"));

        NotificationEntity notification = awaitNotification(eventId);
        assertPendingFor(notification, orderId, NotificationType.ORDER_EXPIRED);
        assertThat(notification.getBody()).contains("nothing was charged");
    }

    @Test
    void orderCancelled_withAnUnknownReason_recordsTheCancellationEmail() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderCancelled",
                EventPayloads.orderCancelled(eventId, orderId, "FRAUD_SUSPECTED"));

        assertPendingFor(awaitNotification(eventId), orderId, NotificationType.ORDER_CANCELLED);
    }

    @Test
    void sameEventTwice_recordsOneNotification() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String event = EventPayloads.orderPlaced(eventId, orderId);

        events.sendOrderEvent(orderId, "OrderPlaced", event);
        events.sendOrderEvent(orderId, "OrderPlaced", event);
        UUID marker = sendMarkerAfter(orderId);

        awaitNotification(marker);
        assertThat(countFor(orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?", Integer.class,
                eventId)).isEqualTo(1);
    }

    @Test
    void twoEventsOfOneOrder_recordTwoNotificationsAndOneRecipient() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID placed = UUID.randomUUID();
        UUID paid = UUID.randomUUID();

        events.sendOrderEvent(orderId, "OrderPlaced", EventPayloads.orderPlaced(placed, orderId));
        events.sendOrderEvent(orderId, "OrderPaid", EventPayloads.orderPaid(paid, orderId));

        awaitNotification(paid);
        assertThat(countFor(orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_recipients WHERE order_id = ?", Integer.class,
                orderId)).isEqualTo(1);
    }

    @Test
    void poisonRecords_goToTheDeadLetterTopic_andRecordNothing() throws Exception {
        UUID key = UUID.randomUUID();
        String withoutEventId = EventPayloads.orderPlaced(UUID.randomUUID(), key)
                .replaceFirst("\"eventId\":\"[^\"]+\",", "");
        String malformedEmail = EventPayloads.orderPaid(UUID.randomUUID(), key)
                .replace(EventPayloads.EMAIL, "not-an-address");
        String shippedWithoutTtn = EventPayloads.orderShipped(UUID.randomUUID(), key)
                .replace("\"ttn\":\"20450000123456\",", "");

        events.sendOrderEvent(key, "OrderPlaced", "this is not JSON");
        events.sendOrderEvent(key, "OrderPlaced", withoutEventId);
        events.sendOrderEvent(key, "OrderPaid", malformedEmail);
        events.sendOrderEvent(key, "OrderShipped", shippedWithoutTtn);
        UUID marker = sendMarkerAfter(key);

        awaitNotification(marker);
        assertThat(countFor(key)).isEqualTo(1);
        await().atMost(WAIT).untilAsserted(() -> assertThat(events.deadLetters(KafkaEvents.ORDER_DLT, key))
                .containsExactly("this is not JSON", withoutEventId, malformedEmail, shippedWithoutTtn));
    }

    @Test
    void transientDatabaseFailure_isRetried_andRecordedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        // The repository is an interface proxy: "real method" = the spy's delegating default answer
        Answer<?> real = mockingDetails(recipients).getMockCreationSettings().getDefaultAnswer();
        doThrow(new TransientDataAccessResourceException("database restarting")).doAnswer(real)
                .when(recipients).upsertRecipient(any(), any(), any());

        events.sendOrderEvent(orderId, "OrderPlaced", EventPayloads.orderPlaced(eventId, orderId));

        assertPendingFor(awaitNotification(eventId), orderId, NotificationType.ORDER_PLACED);
        assertThat(countFor(orderId)).isEqualTo(1);
        assertThat(events.deadLetters(KafkaEvents.ORDER_DLT, orderId)).isEmpty();
    }

    // Same key → same partition: once the marker is recorded, every earlier record of that key was processed
    private UUID sendMarkerAfter(UUID key) throws Exception {
        UUID marker = UUID.randomUUID();
        events.sendOrderEvent(key, "OrderPlaced", EventPayloads.orderPlaced(marker, key));
        return marker;
    }

    private NotificationEntity awaitNotification(UUID eventId) {
        await().atMost(WAIT).until(() -> notifications.findByEventId(eventId).isPresent());
        return notifications.findByEventId(eventId).orElseThrow();
    }

    private static void assertPendingFor(NotificationEntity notification, UUID orderId, NotificationType type) {
        assertThat(notification.getOrderId()).isEqualTo(orderId);
        assertThat(notification.getType()).isEqualTo(type);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getRecipient()).isEqualTo(EventPayloads.EMAIL);
        assertThat(notification.getSubject()).startsWith("VoltStore — ").contains(orderId.toString());
        assertThat(notification.getBody()).isNotBlank();
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getNextAttemptAt()).isNotNull();
    }

    private Integer countFor(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE order_id = ?", Integer.class, orderId);
    }
}
