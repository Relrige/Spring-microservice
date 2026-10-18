package ua.edu.ukma.springers.voltstore.notification.entities;

import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.notification.support.Notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEntityTest {
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    @Test
    void pending_isDueAtOnceWithoutAttempts() {
        NotificationEntity notification = Notifications.pending(NOW);

        assertThat(notification.getId()).isNotNull();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(notification.getCreatedAt()).isEqualTo(NOW);
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    void pending_mayHaveNoRecipient() {
        NotificationEntity notification = Notifications.pending(UUID.randomUUID(), UUID.randomUUID(), null, NOW);

        assertThat(notification.getRecipient()).isNull();
    }

    @Test
    void markSent_endsTheNotification() {
        NotificationEntity notification = Notifications.pending(NOW);

        notification.markSent(NOW.plusSeconds(1));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void markSent_withoutRecipient_throws() {
        NotificationEntity notification = Notifications.pending(UUID.randomUUID(), UUID.randomUUID(), null, NOW);

        assertThatThrownBy(() -> notification.markSent(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignRecipient_thenMarkSent() {
        NotificationEntity notification = Notifications.pending(UUID.randomUUID(), UUID.randomUUID(), null, NOW);

        notification.assignRecipient("late@example.com");
        notification.markSent(NOW);

        assertThat(notification.getRecipient()).isEqualTo("late@example.com");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void recordFailure_countsTheAttemptAndReschedules() {
        NotificationEntity notification = Notifications.pending(NOW);

        notification.recordFailure("smtp_unavailable: connection refused", NOW.plusSeconds(30));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getFailureReason()).isEqualTo("smtp_unavailable: connection refused");
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void markFailed_countsTheAttemptAndEndsTheNotification() {
        NotificationEntity notification = Notifications.pending(NOW);

        notification.markFailed("recipient_rejected");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getFailureReason()).isEqualTo("recipient_rejected");
        assertThat(notification.getNextAttemptAt()).isNull();
    }

    @Test
    void longFailureReason_isCut() {
        NotificationEntity notification = Notifications.pending(NOW);

        notification.recordFailure("x".repeat(5000), NOW);

        assertThat(notification.getFailureReason()).hasSize(NotificationEntity.FAILURE_REASON_MAX_LENGTH);
    }

    @Test
    void finalStates_rejectEveryTransition() {
        NotificationEntity sent = Notifications.pending(NOW);
        sent.markSent(NOW);
        NotificationEntity failed = Notifications.pending(NOW);
        failed.markFailed("recipient_rejected");

        for (NotificationEntity notification : new NotificationEntity[]{sent, failed}) {
            assertThatThrownBy(() -> notification.markSent(NOW)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> notification.markFailed("x")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> notification.recordFailure("x", NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> notification.lease(NOW.plus(Duration.ofMinutes(2))))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> notification.assignRecipient("a@example.com"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
