package ua.edu.ukma.springers.voltstore.notification.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.repositories.OrderRecipientRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * EP-NOTIF-JOB-02: both technical tables hold personal data (addresses, email bodies with order details), so they
 * are not kept forever. Deletes {@code SENT} / {@code FAILED} notifications created, and recipients last updated,
 * more than {@code notification.retention} ago. {@code PENDING} rows are never deleted.
 * <p>
 * Deleting a notification also deletes its dedup key. That is acceptable: Kafka and the producers' outboxes
 * redeliver within minutes, not weeks (the same trade-off as Inventory's {@code processed_events} retention).
 * <p>
 * Known edge: a {@code PENDING} {@code ORDER_DELIVERED} row whose order's recipient was already cleaned up would end
 * {@code FAILED} with {@code recipient_unknown}. With 30 days of retention and deliveries within days, it does not
 * happen in practice.
 * <p>
 * Several replicas need no lock: deleting the same rows twice is harmless.
 */
@Slf4j
@Component
public class CleanupJob {
    private final NotificationRepository notifications;
    private final OrderRecipientRepository recipients;
    private final Duration retention;
    private final Clock clock;

    public CleanupJob(NotificationRepository notifications, OrderRecipientRepository recipients,
                      @Value("${notification.retention}") Duration retention, Clock clock) {
        this.notifications = notifications;
        this.recipients = recipients;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.cleanup.interval}")
    public void run() {
        Instant cutoff = clock.instant().minus(retention);
        int deletedNotifications = notifications.deleteOlderThan(cutoff);
        int deletedRecipients = recipients.deleteOlderThan(cutoff);
        if (deletedNotifications > 0 || deletedRecipients > 0) {
            log.info("Cleanup deleted {} sent/failed notifications and {} recipients older than {}",
                    deletedNotifications, deletedRecipients, cutoff);
        }
    }
}
