package ua.edu.ukma.springers.voltstore.notification.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.edu.ukma.springers.voltstore.notification.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.repositories.OrderRecipientRepository;
import ua.edu.ukma.springers.voltstore.notification.support.MutableClock;
import ua.edu.ukma.springers.voltstore.notification.support.Notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10 (EP-NOTIF-JOB-02) with the default retention of 30 days. The scheduled run is pushed to 1 h and the clock
 * is controlled by the test.
 */
@SpringBootTest(properties = {"notification.sender.enabled=false", "notification.cleanup.interval=1h"})
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, MutableClock.Config.class})
class CleanupJobIntegrationTest {
    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired
    private CleanupJob job;

    @Autowired
    private MutableClock clock;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private OrderRecipientRepository recipients;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM order_recipients");
        clock.reset();
    }

    @Test
    void deletesOldFinishedNotificationsAndRecipients_keepsRecentAndPendingRows() {
        Instant old = clock.instant().minus(RETENTION).minusSeconds(60);
        Instant recent = clock.instant().minus(RETENTION).plusSeconds(60);
        NotificationEntity oldSent = Notifications.pending(old);
        oldSent.markSent(old);
        NotificationEntity oldFailed = Notifications.pending(old);
        oldFailed.markFailed("recipient_rejected: 550");
        NotificationEntity oldPending = Notifications.pending(old);
        NotificationEntity recentSent = Notifications.pending(recent);
        recentSent.markSent(recent);
        NotificationEntity recentPending = Notifications.pending(recent);
        List.of(oldSent, oldFailed, recentSent).forEach(notifications::save);
        List.of(oldPending, recentPending).forEach(notifications::insertIfAbsent);
        UUID oldOrder = UUID.randomUUID();
        UUID recentOrder = UUID.randomUUID();
        recipients.upsertRecipient(oldOrder, "old@example.com", old);
        recipients.upsertRecipient(recentOrder, "recent@example.com", recent);

        job.run();

        assertThat(notifications.findAll()).extracting(NotificationEntity::getId)
                .containsExactlyInAnyOrder(oldPending.getId(), recentSent.getId(), recentPending.getId());
        assertThat(recipients.findRecipient(oldOrder)).isEmpty();
        assertThat(recipients.findRecipient(recentOrder)).contains("recent@example.com");
    }

    @Test
    void rowsBecomeDeletableOnceTheyAreOlderThanTheRetention() {
        NotificationEntity sent = Notifications.pending(clock.instant());
        sent.markSent(clock.instant());
        notifications.save(sent);

        clock.advance(RETENTION.minusMinutes(1));
        job.run();
        assertThat(notifications.findById(sent.getId())).isPresent();

        clock.advance(Duration.ofMinutes(2));
        job.run();
        assertThat(notifications.findById(sent.getId())).isEmpty();
    }
}
