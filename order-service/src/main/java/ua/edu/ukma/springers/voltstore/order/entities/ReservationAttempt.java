package ua.edu.ukma.springers.voltstore.order.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A reservation requested from Inventory for an order that is not stored yet (EP-ORD-02). Written before the call,
 * deleted in the transaction that stores the order; one that outlives the settle time is released by
 * {@code OrphanReservationJob}. Without it, stock reserved for an order that was never stored would stay reserved
 * forever: Inventory reservations do not expire, and only stored orders are expired.
 */
@Entity
@Table(name = "reservation_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationAttempt {
    // last_error column size
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int releaseAttempts;

    private String lastError;

    public ReservationAttempt(UUID orderId, Instant createdAt) {
        this.orderId = Objects.requireNonNull(orderId);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public void recordFailedRelease(String error) {
        this.releaseAttempts++;
        this.lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}
