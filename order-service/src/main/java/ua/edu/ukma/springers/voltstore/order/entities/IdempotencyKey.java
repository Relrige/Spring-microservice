package ua.edu.ukma.springers.voltstore.order.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A submit-order request by {@code Idempotency-Key} (EP-ORD-02, D4). Rows are written only by native statements in
 * {@code IdempotencyKeyRepository} (claim, complete, release, purge), so a concurrent claim never surfaces as a
 * constraint violation; the entity is read-only here.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

    @EmbeddedId
    private Id id;

    @Column(nullable = false, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public enum Status {
        /** Claimed by a request that is still running (or whose instance died; then purged by EP-ORD-JOB-05). */
        IN_PROGRESS,
        /** The order was stored; a repeat returns it. */
        COMPLETED
    }

    /** The key is scoped per customer (D5): the same key from two customers is two independent requests. */
    @Embeddable
    public record Id(@Column(name = "customer_id") UUID customerId,
                     @Column(name = "idempotency_key") UUID idempotencyKey) implements Serializable {
    }
}
