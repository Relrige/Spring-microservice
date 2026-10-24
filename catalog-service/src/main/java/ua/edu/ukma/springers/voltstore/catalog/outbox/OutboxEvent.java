package ua.edu.ukma.springers.voltstore.catalog.outbox;

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

// One event waiting to be (or already) published to Kafka; see V3 migration
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    private static final int LAST_ERROR_MAX_LENGTH = 1000;

    // The event's own eventId, assigned by the caller (not generated), so it matches the payload
    @Id
    private UUID id;

    @Column(nullable = false, length = 50)
    private String aggregateType;

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

    // W3C traceparent of the request that wrote the event (observability task 03); null when written outside a trace
    @Column(length = 55)
    private String traceparent;

    OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic, String messageKey,
                String payload, Instant now, String traceparent) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
        this.traceparent = traceparent;
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
