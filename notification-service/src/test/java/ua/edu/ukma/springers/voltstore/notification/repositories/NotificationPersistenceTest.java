package ua.edu.ukma.springers.voltstore.notification.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.notification.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus;
import ua.edu.ukma.springers.voltstore.notification.support.Concurrently;
import ua.edu.ukma.springers.voltstore.notification.support.Notifications;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Hibernate validate runs on context start, so a passing test also proves the entities match the V1 schema
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
// Each repository call commits on its own, as in production (the claim must commit to release its locks)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationPersistenceTest {
    private static final Duration LEASE = Duration.ofMinutes(2);
    // PostgreSQL stores microseconds
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

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
    }

    @Test
    void insertIfAbsent_storesEveryColumn() {
        NotificationEntity notification = Notifications.pending(now);

        assertThat(notifications.insertIfAbsent(notification)).isTrue();

        NotificationEntity stored = notifications.findById(notification.getId()).orElseThrow();
        assertThat(stored.getEventId()).isEqualTo(notification.getEventId());
        assertThat(stored.getOrderId()).isEqualTo(notification.getOrderId());
        assertThat(stored.getType()).isEqualTo(notification.getType());
        assertThat(stored.getRecipient()).isEqualTo(Notifications.RECIPIENT);
        assertThat(stored.getSubject()).isEqualTo(notification.getSubject());
        assertThat(stored.getBody()).isEqualTo(notification.getBody());
        assertThat(stored.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(stored.getAttempts()).isZero();
        assertThat(stored.getNextAttemptAt()).isEqualTo(now);
        assertThat(stored.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void insertIfAbsent_withoutRecipient() {
        NotificationEntity notification = Notifications.pending(UUID.randomUUID(), UUID.randomUUID(), null, now);

        assertThat(notifications.insertIfAbsent(notification)).isTrue();

        assertThat(notifications.findById(notification.getId()).orElseThrow().getRecipient()).isNull();
    }

    @Test
    void sameEventIdTwice_storesOneRow_andTheSecondCallReturnsFalse() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThat(notifications.insertIfAbsent(Notifications.pending(eventId, orderId, "a@example.com", now)))
                .isTrue();
        assertThat(notifications.insertIfAbsent(Notifications.pending(eventId, orderId, "a@example.com", now)))
                .isFalse();

        assertThat(notifications.count()).isEqualTo(1);
    }

    @Test
    void upsertRecipientTwice_keepsOneRowWithTheNewerTimestamp() {
        UUID orderId = UUID.randomUUID();

        recipients.upsertRecipient(orderId, "user@example.com", now);
        recipients.upsertRecipient(orderId, "user@example.com", now.plusSeconds(60));

        assertThat(recipients.count()).isEqualTo(1);
        assertThat(recipients.findRecipient(orderId)).contains("user@example.com");
        assertThat(recipients.findById(orderId).orElseThrow().getUpdatedAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    void findRecipient_ofAnUnknownOrder_isEmpty() {
        assertThat(recipients.findRecipient(UUID.randomUUID())).isEmpty();
    }

    @Test
    void claimDue_returnsDueRowsOldestFirst_andLeasesThem() {
        NotificationEntity older = Notifications.pending(now.minusSeconds(20));
        NotificationEntity newer = Notifications.pending(now.minusSeconds(10));
        notifications.insertIfAbsent(newer);
        notifications.insertIfAbsent(older);

        List<NotificationEntity> claimed = notifications.claimDue(now, 10, LEASE);

        assertThat(claimed).extracting(NotificationEntity::getId).containsExactly(older.getId(), newer.getId());
        assertThat(notifications.findById(older.getId()).orElseThrow().getNextAttemptAt())
                .isEqualTo(now.plus(LEASE));
        // Leased: not due again until the lease expires
        assertThat(notifications.claimDue(now.plusSeconds(60), 10, LEASE)).isEmpty();
        assertThat(notifications.claimDue(now.plus(LEASE), 10, LEASE)).hasSize(2);
    }

    @Test
    void claimDue_respectsTheBatchSize() {
        for (int i = 0; i < 5; i++) {
            notifications.insertIfAbsent(Notifications.pending(now.minusSeconds(i)));
        }

        assertThat(notifications.claimDue(now, 3, LEASE)).hasSize(3);
    }

    @Test
    void claimDue_skipsFutureSentAndFailedRows() {
        notifications.insertIfAbsent(Notifications.pending(now.plusSeconds(5)));
        NotificationEntity sent = Notifications.pending(now.minusSeconds(5));
        sent.markSent(now);
        notifications.save(sent);
        NotificationEntity failed = Notifications.pending(now.minusSeconds(5));
        failed.markFailed("recipient_rejected");
        notifications.save(failed);

        assertThat(notifications.claimDue(now, 10, LEASE)).isEmpty();
    }

    @Test
    void concurrentClaims_neverReturnTheSameRow() throws Exception {
        for (int i = 0; i < 20; i++) {
            notifications.insertIfAbsent(Notifications.pending(now.minusSeconds(i)));
        }

        List<Object> results = Concurrently.run(4, () -> notifications.claimDue(now, 20, LEASE));

        List<UUID> claimedIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(List.class);
            ((List<?>) result).forEach(row -> claimedIds.add(((NotificationEntity) row).getId()));
        }
        Set<UUID> distinct = new HashSet<>(claimedIds);
        assertThat(distinct).hasSameSizeAs(claimedIds).hasSize(20);
    }

    @Test
    void deleteOlderThan_keepsPendingAndRecentRows() {
        Instant cutoff = now.minus(Duration.ofDays(30));
        NotificationEntity oldSent = Notifications.pending(cutoff.minusSeconds(1));
        oldSent.markSent(cutoff);
        NotificationEntity oldFailed = Notifications.pending(cutoff.minusSeconds(1));
        oldFailed.markFailed("recipient_rejected");
        NotificationEntity oldPending = Notifications.pending(cutoff.minusSeconds(1));
        NotificationEntity recentSent = Notifications.pending(cutoff.plusSeconds(1));
        recentSent.markSent(now);
        List.of(oldSent, oldFailed, recentSent).forEach(notifications::save);
        notifications.insertIfAbsent(oldPending);
        UUID oldOrder = UUID.randomUUID();
        UUID recentOrder = UUID.randomUUID();
        recipients.upsertRecipient(oldOrder, "old@example.com", cutoff.minusSeconds(1));
        recipients.upsertRecipient(recentOrder, "recent@example.com", cutoff.plusSeconds(1));

        assertThat(notifications.deleteOlderThan(cutoff)).isEqualTo(2);
        assertThat(recipients.deleteOlderThan(cutoff)).isEqualTo(1);

        assertThat(notifications.findAll()).extracting(NotificationEntity::getId)
                .containsExactlyInAnyOrder(oldPending.getId(), recentSent.getId());
        assertThat(recipients.findRecipient(oldOrder)).isEmpty();
        assertThat(recipients.findRecipient(recentOrder)).contains("recent@example.com");
    }
}
