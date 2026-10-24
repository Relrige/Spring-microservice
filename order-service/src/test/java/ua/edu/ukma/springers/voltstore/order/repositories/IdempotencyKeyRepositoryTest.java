package ua.edu.ukma.springers.voltstore.order.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statements behind EP-ORD-02 and EP-ORD-JOB-05 against PostgreSQL: claim conflicts, and which rows each purge
 * removes around its boundary (the job itself is tested with the repository mocked in {@code RecoveryJobsTest}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class IdempotencyKeyRepositoryTest {
    // Truncated to microseconds: PostgreSQL stores no finer
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Duration SETTLE = Duration.ofMinutes(2);
    private static final Duration TTL = Duration.ofHours(24);
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String HASH = "a".repeat(64);

    @Autowired
    private IdempotencyKeyRepository keys;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_keys");
    }

    @Test
    void claim_ownsANewKey_andReportsAnExistingOne() {
        UUID key = UUID.randomUUID();

        assertThat(keys.claim(CUSTOMER, key, HASH, NOW)).isOne();
        assertThat(keys.claim(CUSTOMER, key, "b".repeat(64), NOW)).isZero();
        // Scoped per customer
        assertThat(keys.claim(UUID.randomUUID(), key, HASH, NOW)).isOne();
    }

    @Test
    void completeAndRelease_touchOnlyARowStillInProgress() {
        UUID key = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        keys.claim(CUSTOMER, key, HASH, NOW);

        assertThat(keys.complete(CUSTOMER, key, orderId)).isOne();
        assertThat(keys.complete(CUSTOMER, key, UUID.randomUUID())).isZero();
        assertThat(keys.release(CUSTOMER, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT order_id FROM idempotency_keys", UUID.class)).isEqualTo(orderId);
    }

    @Test
    void purgingStaleClaims_deletesOnlyInProgressRowsOlderThanTheSettleTime() {
        UUID younger = row("IN_PROGRESS", NOW.minus(SETTLE).plusSeconds(1));
        UUID older = row("IN_PROGRESS", NOW.minus(SETTLE).minusSeconds(1));
        UUID completedOlder = row("COMPLETED", NOW.minus(SETTLE).minusSeconds(1));

        assertThat(keys.deleteByStatusCreatedBefore("IN_PROGRESS", NOW.minus(SETTLE))).isOne();

        assertThat(remaining()).containsExactlyInAnyOrder(younger, completedOlder).doesNotContain(older);
    }

    @Test
    void purgingExpiredKeys_deletesOnlyCompletedRowsOlderThanTheTtl() {
        UUID younger = row("COMPLETED", NOW.minus(TTL).plusSeconds(1));
        UUID older = row("COMPLETED", NOW.minus(TTL).minusSeconds(1));
        // Past the settle time, well inside the TTL
        UUID completedRecently = row("COMPLETED", NOW.minus(SETTLE).minusSeconds(1));
        UUID claimOlder = row("IN_PROGRESS", NOW.minus(TTL).minusSeconds(1));

        assertThat(keys.deleteByStatusCreatedBefore("COMPLETED", NOW.minus(TTL))).isOne();

        assertThat(remaining()).containsExactlyInAnyOrder(younger, completedRecently, claimOlder)
                .doesNotContain(older);
    }

    private UUID row(String status, Instant createdAt) {
        UUID key = UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_keys (customer_id, idempotency_key, request_hash, status, order_id, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?)", CUSTOMER, key, HASH, status,
                "COMPLETED".equals(status) ? UUID.randomUUID() : null, Timestamp.from(createdAt));
        return key;
    }

    private List<UUID> remaining() {
        return jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys", UUID.class);
    }
}
