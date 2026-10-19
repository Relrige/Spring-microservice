package ua.edu.ukma.springers.voltstore.notification.repositories;

import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// The operations of NotificationRepository that a derived or annotated query cannot express
public interface NotificationRepositoryCustom {

    /**
     * Inserts the notification unless one with the same {@code eventId} exists (the Idempotent Consumer's check).
     *
     * @return {@code true} if inserted, {@code false} if the event was seen before
     */
    boolean insertIfAbsent(NotificationEntity notification);

    /**
     * The sender job's claim, in its own transaction: up to {@code batchSize} due {@code PENDING} rows, oldest
     * {@code next_attempt_at} first, whose {@code next_attempt_at} is moved to {@code now + lease} before the commit.
     * Rows another instance is claiming at the same moment are skipped, not waited for.
     *
     * @return the claimed rows (detached once this method returned)
     */
    List<NotificationEntity> claimDue(Instant now, int batchSize, Duration lease);
}
