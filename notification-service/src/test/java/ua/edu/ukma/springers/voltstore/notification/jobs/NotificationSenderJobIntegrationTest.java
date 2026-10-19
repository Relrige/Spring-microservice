package ua.edu.ukma.springers.voltstore.notification.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ua.edu.ukma.springers.voltstore.notification.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.email.EmailSendException;
import ua.edu.ukma.springers.voltstore.notification.email.EmailSender;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.repositories.OrderRecipientRepository;
import ua.edu.ukma.springers.voltstore.notification.support.Concurrently;
import ua.edu.ukma.springers.voltstore.notification.support.MailpitContainer;
import ua.edu.ukma.springers.voltstore.notification.support.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Task 09 (EP-NOTIF-JOB-01) against PostgreSQL and a real Mailpit. The clock is moved by the test instead of
 * sleeping, and the scheduled run is pushed to 1 h, so only the test's own {@code runOnce()} calls send. The
 * {@link EmailSender} is a spy on the SMTP adapter: it really sends unless a test makes it fail.
 */
@SpringBootTest(properties = "notification.sender.interval=1h")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, MailpitContainer.Config.class,
        MutableClock.Config.class})
class NotificationSenderJobIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final EmailSendException UNAVAILABLE =
            new EmailSendException("smtp_unavailable: ConnectException: Connection refused", true, null);

    @Autowired
    private NotificationSenderJob job;

    @Autowired
    private MailpitContainer mailpit;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private EmailSender emailSender;

    @MockitoSpyBean
    private NotificationRepository notifications;

    @Autowired
    private OrderRecipientRepository recipients;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM order_recipients");
        mailpit.clear();
        clock.reset();
        reset(emailSender);
    }

    @Test
    void pendingNotification_isSentAndMarkedSent() {
        NotificationEntity notification = pending(address(), NotificationType.ORDER_PLACED);

        assertThat(job.runOnce()).isEqualTo(1);

        NotificationEntity sent = reload(notification);
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getSentAt()).isEqualTo(clock.instant());
        assertThat(sent.getNextAttemptAt()).isNull();
        assertThat(sent.getAttempts()).isZero();
        MailpitContainer.Message message = awaitOneMessageTo(notification.getRecipient());
        assertThat(message.subject()).isEqualTo(notification.getSubject());
        assertThat(mailpit.text(message.id())).contains(notification.getBody().strip());
    }

    @Test
    void twoFailuresThenSuccess_isSentOnTheThirdAttempt_afterTheBackoffs() {
        doThrow(UNAVAILABLE).doThrow(UNAVAILABLE).doCallRealMethod().when(emailSender).send(any());
        NotificationEntity notification = pending(address(), NotificationType.ORDER_PLACED);
        Instant start = clock.instant();

        job.runOnce();
        assertThat(reload(notification).getAttempts()).isEqualTo(1);
        assertThat(reload(notification).getNextAttemptAt()).isEqualTo(start.plusSeconds(30));
        assertThat(reload(notification).getFailureReason()).isEqualTo(UNAVAILABLE.getReason());
        // Not due before its back-off
        assertThat(job.runOnce()).isZero();

        clock.advance(Duration.ofSeconds(30));
        job.runOnce();
        assertThat(reload(notification).getAttempts()).isEqualTo(2);
        assertThat(reload(notification).getNextAttemptAt()).isEqualTo(start.plusSeconds(30).plus(Duration.ofMinutes(2)));

        clock.advance(Duration.ofMinutes(2));
        job.runOnce();
        assertThat(reload(notification).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reload(notification).getAttempts()).isEqualTo(2);
        awaitOneMessageTo(notification.getRecipient());
    }

    @Test
    void alwaysFailing_isFailedAfterFiveAttemptsWithTheLastReason() {
        doThrow(new EmailSendException("smtp_temporary: 421", true, null)).when(emailSender).send(any());
        NotificationEntity notification = pending(address(), NotificationType.ORDER_PLACED);
        Duration[] backoffs = {Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
                Duration.ofMinutes(30)};

        job.runOnce();
        for (Duration backoff : backoffs) {
            clock.advance(backoff);
            assertThat(job.runOnce()).isEqualTo(1);
        }

        NotificationEntity failed = reload(notification);
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(5);
        assertThat(failed.getFailureReason()).isEqualTo("smtp_temporary: 421");
        assertThat(failed.getNextAttemptAt()).isNull();
        verify(emailSender, times(5)).send(any());
        clock.advance(Duration.ofDays(1));
        assertThat(job.runOnce()).isZero();
    }

    @Test
    void rejectedRecipient_isFailedAfterOneAttempt() {
        doThrow(new EmailSendException("recipient_rejected: 550", false, null)).when(emailSender).send(any());
        NotificationEntity notification = pending(address(), NotificationType.ORDER_PLACED);

        job.runOnce();

        NotificationEntity failed = reload(notification);
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getFailureReason()).isEqualTo("recipient_rejected: 550");
        verify(emailSender, times(1)).send(any());
    }

    @Test
    void deliveredWithoutKnownRecipient_isRetried_andSentOnceTheRecipientIsKnown() {
        NotificationEntity notification = pending(null, NotificationType.ORDER_DELIVERED);

        job.runOnce();
        NotificationEntity waiting = reload(notification);
        assertThat(waiting.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(waiting.getFailureReason()).isEqualTo("recipient_unknown");
        assertThat(waiting.getRecipient()).isNull();
        verify(emailSender, times(0)).send(any());

        // The order's events arrive on Order's topic meanwhile
        String address = address();
        recipients.upsertRecipient(notification.getOrderId(), address, clock.instant());
        clock.advance(Duration.ofSeconds(30));
        job.runOnce();

        NotificationEntity sent = reload(notification);
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getRecipient()).isEqualTo(address);
        awaitOneMessageTo(address);
    }

    @Test
    void notificationsDueInTheFuture_areNotPicked() {
        NotificationEntity notification = pending(address(), NotificationType.ORDER_PLACED);
        clock.advance(Duration.ofSeconds(-1));

        assertThat(job.runOnce()).isZero();
        assertThat(reload(notification).getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void twoJobInstancesAtOnce_sendEachEmailOnce() throws Exception {
        String address = address();
        for (int i = 0; i < 10; i++) {
            pending(address, NotificationType.ORDER_PLACED);
        }

        Concurrently.run(2, job::runOnce);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status = 'SENT'", Integer.class))
                .isEqualTo(10);
        verify(emailSender, times(10)).send(any());
        await().atMost(WAIT).untilAsserted(() -> assertThat(mailpit.messagesTo(address)).hasSize(10));
    }

    @Test
    void stopAfterSendBeforeTheResultIsStored_sendsAgainAfterTheLease() {
        NotificationEntity notification = pending(address(), NotificationType.ORDER_PLACED);
        // The email goes out, then storing the result fails, like a crash between the two
        Answer<?> real = mockingDetails(notifications).getMockCreationSettings().getDefaultAnswer();
        doThrow(new TransientDataAccessResourceException("instance stopped")).doAnswer(real)
                .when(notifications).findById(notification.getId());

        job.runOnce();
        assertThat(reload(notification).getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(job.runOnce()).as("leased, not due yet").isZero();

        clock.advance(LEASE);
        job.runOnce();

        assertThat(reload(notification).getStatus()).isEqualTo(NotificationStatus.SENT);
        // The documented at-least-once window: the customer got this email twice
        await().atMost(WAIT).untilAsserted(() -> assertThat(mailpit.messagesTo(notification.getRecipient()))
                .hasSize(2));
    }

    @Test
    void oneFailingRow_doesNotStopTheRun() {
        NotificationEntity broken = pending(address(), NotificationType.ORDER_PLACED);
        NotificationEntity fine = pending(address(), NotificationType.ORDER_SHIPPED);
        Answer<?> real = mockingDetails(notifications).getMockCreationSettings().getDefaultAnswer();
        doThrow(new IllegalStateException("unexpected")).doAnswer(real).when(notifications).findById(broken.getId());

        assertThat(job.runOnce()).isEqualTo(2);

        assertThat(reload(fine).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reload(broken).getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    private NotificationEntity pending(String recipient, NotificationType type) {
        UUID orderId = UUID.randomUUID();
        NotificationEntity notification = NotificationEntity.pending(UUID.randomUUID(), orderId, type, recipient,
                "VoltStore — Order #" + orderId + " " + type, "Hello,\n\nbody of " + type + "\n", clock.instant());
        assertThat(notifications.insertIfAbsent(notification)).isTrue();
        return notification;
    }

    private NotificationEntity reload(NotificationEntity notification) {
        return notifications.findById(notification.getId()).orElseThrow();
    }

    private MailpitContainer.Message awaitOneMessageTo(String address) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(mailpit.messagesTo(address)).hasSize(1));
        return mailpit.messagesTo(address).getFirst();
    }

    private static String address() {
        return "buyer-" + UUID.randomUUID() + "@example.com";
    }
}
