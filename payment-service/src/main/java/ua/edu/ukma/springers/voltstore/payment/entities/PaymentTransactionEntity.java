package ua.edu.ukma.springers.voltstore.payment.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One request to the payment gateway and its outcome. Rows are append-only: a refund is a new row, not a change of
 * the charge, so the table is the full payment history of an order. Instances are created only through the factory
 * methods, which keep the success/failure invariants that the table's CHECK constraints also enforce.
 * <p>
 * No card data is ever stored here, not even masked.
 */
@Entity
@Table(name = "payment_transactions")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    private String gatewayTransactionId;

    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    @Column(nullable = false)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(nullable = false)
    private Instant createdAt;

    private PaymentTransactionEntity(UUID orderId, TransactionType type, TransactionStatus status, BigDecimal amount,
                                     String currency, String gatewayTransactionId, FailureReason failureReason,
                                     String idempotencyKey) {
        this.orderId = Objects.requireNonNull(orderId);
        this.type = type;
        this.status = status;
        this.amount = Objects.requireNonNull(amount);
        this.currency = Objects.requireNonNull(currency);
        this.gatewayTransactionId = gatewayTransactionId;
        this.failureReason = failureReason;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
    }

    public static PaymentTransactionEntity succeeded(TransactionType type, UUID orderId, BigDecimal amount,
                                                     String currency, String gatewayTransactionId,
                                                     String idempotencyKey) {
        return new PaymentTransactionEntity(orderId, type, TransactionStatus.SUCCESS, amount, currency,
                Objects.requireNonNull(gatewayTransactionId), null, idempotencyKey);
    }

    public static PaymentTransactionEntity failed(TransactionType type, UUID orderId, BigDecimal amount,
                                                  String currency, FailureReason reason, String idempotencyKey) {
        return new PaymentTransactionEntity(orderId, type, TransactionStatus.FAILED, amount, currency, null,
                Objects.requireNonNull(reason), idempotencyKey);
    }
}
