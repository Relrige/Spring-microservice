package ua.edu.ukma.springers.voltstore.catalog.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    /**
     * Claims due, unpublished events, oldest first, by locking their rows until the caller's transaction ends.
     * SKIP LOCKED makes another relay instance (the Deployment runs 2 replicas) skip rows that are already
     * claimed instead of waiting for them or publishing them a second time.
     */
    @NativeQuery("""
            SELECT * FROM outbox_events
            WHERE published_at IS NULL AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxEvent> claimDue(Instant now, int limit);

    // Backlog size for the gauge in OutboxMetrics; uses the partial index on unpublished events
    long countByPublishedAtIsNull();

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(Instant cutoff);
}
