package ua.edu.ukma.springers.voltstore.order.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Money that must go back: the card was charged for an order that was cancelled or expired during the charge
 * (EP-ORD-03 step 5). Written before the first refund attempt and deleted once Payment confirms it, so a failed or
 * interrupted refund is repeated by {@code PendingRefundJob} instead of being forgotten. The cancellation saga does
 * not cover these orders: they were never {@code PAID}, so the saga owes no refund for them.
 */
@Entity
@Table(name = "pending_refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PendingRefund {
    // last_error column size
    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID orderId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Instant lastAttemptAt;

    private String lastError;

    @Column(nullable = false)
    private boolean needsManualAttention;

    public PendingRefund(UUID orderId, BigDecimal amount, String currency, Instant now) {
        this.orderId = Objects.requireNonNull(orderId);
        this.amount = Objects.requireNonNull(amount);
        this.currency = Objects.requireNonNull(currency);
        this.createdAt = Objects.requireNonNull(now);
        this.lastAttemptAt = now;
    }

    /** Starts an attempt; saved before it, so the job does not pick the refund again while it runs. */
    public void startAttempt(Instant now) {
        this.lastAttemptAt = now;
    }

    /**
     * @return whether this failure flagged the refund for a person (it happens once, at the limit)
     */
    public boolean recordFailure(String error, Instant now, int manualAttentionAfter) {
        this.attempts++;
        this.lastAttemptAt = now;
        this.lastError = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (!needsManualAttention && attempts >= manualAttentionAfter) {
            this.needsManualAttention = true;
            return true;
        }
        return false;
    }
}
