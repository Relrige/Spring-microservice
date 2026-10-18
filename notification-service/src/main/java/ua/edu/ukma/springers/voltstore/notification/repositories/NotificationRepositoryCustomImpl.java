package ua.edu.ukma.springers.voltstore.notification.repositories;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

class NotificationRepositoryCustomImpl implements NotificationRepositoryCustom {
    private final JdbcClient jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    NotificationRepositoryCustomImpl(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Plain JDBC, not {@code persist}: a duplicate must not raise a constraint violation, which would mark the
     * caller's transaction rollback-only and leave the Hibernate session unusable. The statement still joins the
     * caller's transaction, because the JPA transaction manager exposes its connection to JDBC.
     */
    @Override
    @Transactional
    public boolean insertIfAbsent(NotificationEntity n) {
        int inserted = jdbc.sql("""
                        INSERT INTO notifications (id, event_id, order_id, type, recipient, subject, body, status,
                                                   attempts, next_attempt_at, created_at)
                        VALUES (:id, :eventId, :orderId, :type, :recipient, :subject, :body, :status,
                                :attempts, :nextAttemptAt, :createdAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", n.getId())
                .param("eventId", n.getEventId())
                .param("orderId", n.getOrderId())
                .param("type", n.getType().name())
                .param("recipient", n.getRecipient())
                .param("subject", n.getSubject())
                .param("body", n.getBody())
                .param("status", n.getStatus().name())
                .param("attempts", n.getAttempts())
                .param("nextAttemptAt", Timestamp.from(n.getNextAttemptAt()))
                .param("createdAt", Timestamp.from(n.getCreatedAt()))
                .update();
        return inserted == 1;
    }

    /**
     * {@code FOR UPDATE SKIP LOCKED} locks the selected rows until the commit, so a concurrent claim skips them;
     * after the commit the lease makes them not due. The lease is written by dirty checking at the commit.
     */
    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<NotificationEntity> claimDue(Instant now, int batchSize, Duration lease) {
        List<NotificationEntity> due = entityManager.createNativeQuery("""
                        SELECT * FROM notifications
                        WHERE status = 'PENDING' AND next_attempt_at <= :now
                        ORDER BY next_attempt_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """, NotificationEntity.class)
                .setParameter("now", now)
                .setParameter("limit", batchSize)
                .getResultList();
        Instant until = now.plus(lease);
        due.forEach(notification -> notification.lease(until));
        return due;
    }
}
