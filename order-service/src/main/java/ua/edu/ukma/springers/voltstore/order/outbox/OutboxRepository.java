package ua.edu.ukma.springers.voltstore.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    /**
     * Takes the relay lock for the rest of the caller's transaction, without waiting: {@code false} means another
     * relay instance (with several replicas) is publishing right now. A transaction-level advisory lock
     * is released automatically on commit or rollback, also when the instance crashes (its connection closes).
     */
    @NativeQuery("SELECT pg_try_advisory_xact_lock(:key)")
    boolean tryRelayLock(long key);

    /**
     * Unpublished events that are due, in publishing order, skipping every order that has an earlier
     * unpublished event still waiting for its retry: a later event of an order must never overtake an earlier
     * one. Other orders are not affected.
     */
    @NativeQuery("""
            SELECT * FROM outbox_events e
            WHERE e.published_at IS NULL
              AND e.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events earlier
                  WHERE earlier.aggregate_id = e.aggregate_id
                    AND earlier.published_at IS NULL
                    AND earlier.sequence_number < e.sequence_number
                    AND earlier.next_attempt_at > :now)
            ORDER BY e.sequence_number
            LIMIT :limit
            """)
    List<OutboxEvent> findDue(Instant now, int limit);

    // For the "oldest unpublished event" gauge and log line
    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Optional<Instant> findOldestUnpublishedCreatedAt();

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(Instant cutoff);
}
