package ua.edu.ukma.springers.voltstore.notification.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One email to send (spec, <i>Notification log</i>). An event handler creates it {@code PENDING}, with the subject
 * and body already rendered; afterwards only the sender job changes it. Every state change goes through the
 * transition methods below, and a change from a final state throws {@link IllegalStateException}.
 * <p>
 * The row is inserted with {@code INSERT … ON CONFLICT (event_id) DO NOTHING} (the dedup key), not with
 * {@code persist}, so its ID is assigned here instead of by Hibernate.
 * <p>
 * No {@code @Version}: after the insert only the job writes a row, and the claim's lease keeps two job instances
 * from working on the same row.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEntity {
    // Longer error messages are cut; the reason is for operators, not a full stack trace
    public static final int FAILURE_REASON_MAX_LENGTH = 1000;

    @Id
    private UUID id;

    // The consumed event's ID (dedup key); unique
    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private NotificationType type;

    // Null until resolved from order_recipients (ORDER_DELIVERED, spec D1)
    @Column(length = 256)
    private String recipient;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String subject;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    // Failed sending attempts so far
    @Column(nullable = false)
    private int attempts;

    // When the job may pick the row next; null once it left PENDING
    private Instant nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    /** A new notification, due at once. {@code recipient} is null when it must be resolved at send time. */
    public static NotificationEntity pending(UUID eventId, UUID orderId, NotificationType type, String recipient,
                                             String subject, String body, Instant now) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(now, "now must not be null");
        NotificationEntity notification = new NotificationEntity();
        notification.id = UUID.randomUUID();
        notification.eventId = eventId;
        notification.orderId = orderId;
        notification.type = type;
        notification.recipient = recipient;
        notification.subject = subject;
        notification.body = body;
        notification.status = NotificationStatus.PENDING;
        notification.attempts = 0;
        notification.nextAttemptAt = now;
        notification.createdAt = now;
        return notification;
    }

    /** The claim's lease: no other job instance picks the row before {@code until}. */
    public void lease(Instant until) {
        requirePending("lease");
        this.nextAttemptAt = Objects.requireNonNull(until, "until must not be null");
    }

    /** Stores the address the job resolved from the recipient read model. */
    public void assignRecipient(String recipient) {
        requirePending("assign a recipient to");
        this.recipient = Objects.requireNonNull(recipient, "recipient must not be null");
    }

    /** The SMTP server accepted the message. */
    public void markSent(Instant now) {
        requirePending("mark sent");
        if (recipient == null) {
            throw new IllegalStateException("Notification " + id + " has no recipient and cannot have been sent");
        }
        this.status = NotificationStatus.SENT;
        this.sentAt = Objects.requireNonNull(now, "now must not be null");
        this.nextAttemptAt = null;
    }

    /** A failed attempt; the notification is tried again at {@code nextAttemptAt}. */
    public void recordFailure(String reason, Instant nextAttemptAt) {
        requirePending("record a failure of");
        this.attempts++;
        this.failureReason = truncate(reason);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    }

    /** A failed attempt after which the notification is given up (address rejected, or retries used up). */
    public void markFailed(String reason) {
        requirePending("mark failed");
        this.attempts++;
        this.status = NotificationStatus.FAILED;
        this.failureReason = truncate(reason);
        this.nextAttemptAt = null;
    }

    private void requirePending(String action) {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException("Cannot " + action + " notification " + id + " in status " + status);
        }
    }

    private static String truncate(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        return reason.length() <= FAILURE_REASON_MAX_LENGTH ? reason : reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }
}
