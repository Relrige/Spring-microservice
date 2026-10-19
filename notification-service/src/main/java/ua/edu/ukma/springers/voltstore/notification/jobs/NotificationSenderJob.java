package ua.edu.ukma.springers.voltstore.notification.jobs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.notification.email.EmailMessage;
import ua.edu.ukma.springers.voltstore.notification.email.EmailSendException;
import ua.edu.ukma.springers.voltstore.notification.email.EmailSender;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus;
import ua.edu.ukma.springers.voltstore.notification.metrics.NotificationMetrics;
import ua.edu.ukma.springers.voltstore.notification.metrics.NotificationMetrics.Outcome;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.repositories.OrderRecipientRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * EP-NOTIF-JOB-01: turns {@code PENDING} notifications into sent emails. Same shape as Delivery's TTN job:
 * <ol>
 *   <li><b>Claim</b> due rows ({@code PENDING}, {@code next_attempt_at <= now}, oldest first) with
 *   {@code FOR UPDATE SKIP LOCKED}, move their {@code next_attempt_at} forward by a lease and commit, before any SMTP
 *   call. Another replica (or a second run) skips the locked rows and, after the commit, sees them as not due.</li>
 *   <li><b>Attempt</b> each one outside any transaction: resolve a missing recipient from the read model (an
 *   {@code ORDER_DELIVERED} row, spec D1), then send through {@link EmailSender}. No row lock or transaction is held
 *   during the lookup or the SMTP call.</li>
 *   <li><b>Apply</b> the result in a new transaction: sent → {@code SENT}; retryable failure → one more attempt after
 *   the next back-off, or {@code FAILED} when they are used up; permanent failure → {@code FAILED} at once.</li>
 * </ol>
 * <b>At-least-once:</b> if the instance stops after the SMTP server accepted a message but before the row is marked
 * {@code SENT}, the lease expires and the email is sent again. SMTP has no idempotency key, so this window cannot be
 * closed; a rare duplicate is accepted. An email is never lost: the row is marked {@code SENT} only after the server
 * accepted it.
 * <p>
 * <b>Why database-driven retries</b> instead of Resilience4j {@code Retry} or Spring Retry: those repeat a call
 * within one request, a few seconds apart, in memory. These back-offs are up to 30 minutes long and must survive a
 * restart, and retrying inside the Kafka listener would block the partition. The state lives in
 * {@code attempts} / {@code next_attempt_at}, and the pending rows are visible in a metric.
 * <p>
 * Logging names the notification, order, type, attempt and reason, never the address or the body. Each applied
 * attempt is counted in {@code voltstore_notifications_total} after its transaction committed.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "notification.sender", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SenderJobProperties.class)
public class NotificationSenderJob {
    private final NotificationRepository notifications;
    private final OrderRecipientRepository recipients;
    private final EmailSender emailSender;
    private final SenderJobProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final NotificationMetrics metrics;

    public NotificationSenderJob(NotificationRepository notifications, OrderRecipientRepository recipients,
                                 EmailSender emailSender, SenderJobProperties properties,
                                 PlatformTransactionManager transactionManager, Clock clock,
                                 NotificationMetrics metrics) {
        this.notifications = notifications;
        this.recipients = recipients;
        this.emailSender = emailSender;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.metrics = metrics;
    }

    // The first run waits one interval too, so a run never starts while the context is still starting
    @Scheduled(fixedDelayString = "${notification.sender.interval}",
            initialDelayString = "${notification.sender.interval}")
    public void run() {
        // Keep going while full batches are claimed, so a backlog drains without waiting for the next run
        while (runOnce() == properties.batchSize()) {
            log.debug("Full batch of notifications processed; claiming the next one");
        }
    }

    /** One claim and the attempts of the claimed notifications; returns how many were claimed. */
    public int runOnce() {
        List<NotificationEntity> claimed = notifications.claimDue(clock.instant(), properties.batchSize(),
                properties.lease());
        for (NotificationEntity notification : claimed) {
            try {
                SendOutcome outcome = attempt(notification);
                Outcome applied = transactionTemplate.execute(status -> apply(notification, outcome));
                if (applied != null) {
                    metrics.record(notification.getType(), applied);
                }
            } catch (RuntimeException e) {
                // E.g. the database is down while applying the result: the lease expires and the attempt is redone
                log.error("Notification {} ({} of order {}) could not be completed; it is retried after the lease",
                        notification.getId(), notification.getType(), notification.getOrderId(), e);
            }
        }
        return claimed.size();
    }

    // Outside any transaction: the read-model lookup and the SMTP call
    private SendOutcome attempt(NotificationEntity notification) {
        String recipient = notification.getRecipient() != null
                ? notification.getRecipient()
                : recipients.findRecipient(notification.getOrderId()).orElse(null);
        if (recipient == null) {
            // The order's events on Order's topic are still behind (or were never seen): wait for them
            return new SendOutcome.Failure(null, "recipient_unknown", true);
        }
        try {
            emailSender.send(new EmailMessage(recipient, notification.getSubject(), notification.getBody()));
            return new SendOutcome.Sent(recipient);
        } catch (EmailSendException e) {
            return new SendOutcome.Failure(recipient, e.getReason(), e.isRetryable());
        }
    }

    // Returns what was stored, or null if the result was discarded
    private Outcome apply(NotificationEntity claimed, SendOutcome outcome) {
        NotificationEntity notification = notifications.findById(claimed.getId()).orElseThrow();
        if (notification.getStatus() != NotificationStatus.PENDING) {
            // Another instance finished it after our lease expired; its result stands
            log.warn("Notification {} of order {} is already {}; this attempt's result is discarded",
                    notification.getId(), notification.getOrderId(), notification.getStatus());
            return null;
        }
        if (notification.getRecipient() == null && outcome.recipient() != null) {
            notification.assignRecipient(outcome.recipient());
        }
        Instant now = clock.instant();
        int attempt = notification.getAttempts() + 1;
        return switch (outcome) {
            case SendOutcome.Sent ignored -> {
                notification.markSent(now);
                log.info("Sent {} notification {} of order {} (attempt {})", notification.getType(),
                        notification.getId(), notification.getOrderId(), attempt);
                yield Outcome.SENT;
            }
            case SendOutcome.Failure failure when failure.retryable()
                    && notification.getAttempts() < properties.backoff().size() -> {
                Duration backoff = properties.backoff().get(notification.getAttempts());
                notification.recordFailure(failure.reason(), now.plus(backoff));
                log.warn("Sending {} notification {} of order {} failed (attempt {}): {}; next attempt at {}",
                        notification.getType(), notification.getId(), notification.getOrderId(), attempt,
                        failure.reason(), notification.getNextAttemptAt());
                yield Outcome.RETRY;
            }
            case SendOutcome.Failure failure when failure.retryable() -> {
                notification.markFailed(failure.reason());
                log.error("Giving up {} notification {} of order {} after {} attempts: {}", notification.getType(),
                        notification.getId(), notification.getOrderId(), attempt, failure.reason());
                yield Outcome.FAILED;
            }
            case SendOutcome.Failure failure -> {
                notification.markFailed(failure.reason());
                log.warn("{} notification {} of order {} failed permanently (attempt {}): {}", notification.getType(),
                        notification.getId(), notification.getOrderId(), attempt, failure.reason());
                yield Outcome.FAILED;
            }
        };
    }
}
