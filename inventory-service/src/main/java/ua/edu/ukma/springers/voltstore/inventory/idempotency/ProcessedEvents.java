package ua.edu.ukma.springers.voltstore.inventory.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotent Consumer: remembers which events a handler has already applied (table {@code processed_events}).
 * <p>
 * {@link #markProcessed} must run in the handler's own transaction, before the business change: both commit or
 * roll back together (one DataSource, one transaction manager). Two concurrent deliveries of the same event are
 * serialized by the primary key: the second insert waits for the first transaction and then inserts nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEvents {
    private final JdbcTemplate jdbcTemplate;

    @Value("${inventory.idempotency.retention}")
    private Duration retention;

    /**
     * @return true if this is the first time {@code consumer} sees {@code eventId} (go ahead), false for a
     *         duplicate (skip the business change)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbcTemplate.update("""
                INSERT INTO processed_events (event_id, consumer, processed_at) VALUES (?, ?, ?)
                ON CONFLICT (event_id, consumer) DO NOTHING
                """, eventId, consumer, Timestamp.from(Instant.now())) == 1;
    }

    /**
     * Keys are only needed while a redelivery is still possible (retry topics, a Catalog outbox retrying for
     * minutes), so they are deleted after the retention period, which must be far longer than that.
     */
    @Scheduled(fixedDelayString = "${inventory.idempotency.cleanup-interval}")
    @Transactional
    public int deleteExpired() {
        int deleted = jdbcTemplate.update("DELETE FROM processed_events WHERE processed_at < ?",
                Timestamp.from(Instant.now().minus(retention)));
        if (deleted > 0) {
            log.info("Deleted {} processed-event keys older than {}", deleted, retention);
        }
        return deleted;
    }
}
