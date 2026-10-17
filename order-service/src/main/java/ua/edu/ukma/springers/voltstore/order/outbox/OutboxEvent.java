package ua.edu.ukma.springers.voltstore.order.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// One order event waiting to be (or already) published to Kafka; see the V2 migration
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    // The event's own eventId, assigned by the caller (not generated), so it matches the payload
    @Id
    private UUID id;

    // Assigned by the database (identity column) on insert; only read by the relay
    @Column(insertable = false, updatable = false)
    private Long sequenceNumber;

    // The orderId
    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(length = LAST_ERROR_MAX_LENGTH)
    private String lastError;

    OutboxEvent(UUID id, UUID aggregateId, String eventType, String topic, String messageKey, String payload,
                Instant now) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    void markPublished(Instant now) {
        this.publishedAt = now;
        this.lastError = null;
    }

    void markFailed(Instant now, Duration retryAfter, String error) {
        this.attempts++;
        this.nextAttemptAt = now.plus(retryAfter);
        this.lastError = error != null && error.length() > LAST_ERROR_MAX_LENGTH
                ? error.substring(0, LAST_ERROR_MAX_LENGTH) : error;
    }
}
