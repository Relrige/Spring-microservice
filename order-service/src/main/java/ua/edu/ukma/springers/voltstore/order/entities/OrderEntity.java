package ua.edu.ukma.springers.voltstore.order.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root of the order lifecycle. The status changes only through the transition methods below, which
 * enforce the state machine (docs/design/entities/order-state-machine.md); an illegal transition throws
 * {@link InvalidOrderStateException}. Concurrent changes of one order are detected by {@link #version}: the loser's
 * flush fails and it re-reads the order.
 * <p>
 * The ID is assigned by Order Service before the order is stored, because Inventory reserves stock under it first.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Embedded
    private DeliveryDetails deliveryDetails;

    @Column(nullable = false, updatable = false)
    private Instant reservationExpiresAt;

    @Column(nullable = false, updatable = false)
    private Instant placedAt;

    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    private OrderStatus statusBeforeCancellation;

    @Column(nullable = false)
    private int cancellationAttempts;

    private String cancellationFailure;

    private Instant cancellationLastAttemptAt;

    @Column(nullable = false)
    private boolean needsManualAttention;

    // Null until the first save, which is how Spring Data tells a new order (with an assigned ID) from an old one
    @Version
    private Long version;

    // Snapshots are written once with the order and never changed; unidirectional, the line knows no order
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private List<OrderItemEntity> items = new ArrayList<>();

    private OrderEntity(UUID id, UUID customerId, String customerEmail, DeliveryDetails deliveryDetails,
                        List<OrderItemEntity> items, String currency, Instant placedAt, Instant reservationExpiresAt) {
        this.id = id;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.deliveryDetails = deliveryDetails;
        this.items = new ArrayList<>(items);
        this.currency = currency;
        this.placedAt = placedAt;
        this.reservationExpiresAt = reservationExpiresAt;
        this.totalAmount = items.stream().map(OrderItemEntity::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.status = OrderStatus.PENDING_PAYMENT;
    }

    /**
     * A new order waiting for payment. The total is computed from the snapshots, never taken from the client.
     *
     * @param paymentWindow how long the customer may pay; the reservation is released after it
     */
    public static OrderEntity place(UUID id, UUID customerId, String customerEmail, DeliveryDetails deliveryDetails,
                                    List<OrderItemEntity> items, String currency, Instant placedAt,
                                    Duration paymentWindow) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(customerId);
        Objects.requireNonNull(customerEmail);
        Objects.requireNonNull(deliveryDetails);
        Objects.requireNonNull(currency);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item");
        }
        Set<UUID> productIds = new HashSet<>();
        for (OrderItemEntity item : items) {
            if (!productIds.add(item.getProductId())) {
                throw new IllegalArgumentException("Product " + item.getProductId() + " appears twice");
            }
        }
        return new OrderEntity(id, customerId, customerEmail, deliveryDetails, items, currency, placedAt,
                placedAt.plus(paymentWindow));
    }

    public List<OrderItemEntity> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** {@code PENDING_PAYMENT → PAID} (EP-ORD-03). */
    public void markPaid(Instant now) {
        require(OrderStatus.PENDING_PAYMENT, "be paid");
        this.status = OrderStatus.PAID;
        this.paidAt = now;
    }

    /** {@code PAID → PENDING_SHIPPING} when Delivery reports the TTN (EP-ORD-EVT-01). */
    public void markPendingShipping() {
        require(OrderStatus.PAID, "move to pending shipping");
        this.status = OrderStatus.PENDING_SHIPPING;
    }

    /**
     * {@code PAID | PENDING_SHIPPING → SHIPPED} (EP-ORD-08). An order still {@code PAID} may be shipped when Delivery
     * already has its TTN and only the {@code TTNGenerated} event is late.
     */
    public void markShipped() {
        if (!OrderStatus.AWAITING_DISPATCH.contains(status)) {
            throw new InvalidOrderStateException(id, status, "be shipped");
        }
        this.status = OrderStatus.SHIPPED;
    }

    /** {@code SHIPPED → DELIVERED} when the carrier reports the delivery (EP-ORD-EVT-02). */
    public void markDelivered() {
        require(OrderStatus.SHIPPED, "be delivered");
        this.status = OrderStatus.DELIVERED;
    }

    /** {@code PENDING_PAYMENT → EXPIRED} after the payment window (EP-ORD-JOB-01). No refund is owed. */
    public void expire() {
        require(OrderStatus.PENDING_PAYMENT, "expire");
        this.status = OrderStatus.EXPIRED;
    }

    /**
     * Starts the cancellation saga (EP-ORD-06) and remembers where it started, which decides whether a refund is
     * owed. A second request while one is in progress is rejected. The first saga attempt starts now, so the job
     * that finishes interrupted cancellations (EP-ORD-JOB-02) waits its back-off before touching this order.
     */
    public void requestCancellation(Instant now) {
        if (!OrderStatus.CANCELLABLE.contains(status)) {
            throw new InvalidOrderStateException(id, status, "be cancelled");
        }
        this.statusBeforeCancellation = status;
        this.status = OrderStatus.CANCELLATION_REQUESTED;
        this.cancellationLastAttemptAt = now;
    }

    /**
     * The job starts another saga attempt (EP-ORD-JOB-02). Saved before the attempt, it is the job's claim on the
     * order: a second job instance that read the same version fails on {@link #version} and leaves the order alone.
     */
    public void startCancellationAttempt(Instant now) {
        require(OrderStatus.CANCELLATION_REQUESTED, "retry the cancellation");
        this.cancellationLastAttemptAt = now;
    }

    /** {@code CANCELLATION_REQUESTED → CANCELLED} once every saga step succeeded. */
    public void markCancelled() {
        require(OrderStatus.CANCELLATION_REQUESTED, "finish cancelling");
        this.status = OrderStatus.CANCELLED;
        this.cancellationFailure = null;
    }

    /**
     * Records a failed saga attempt (EP-ORD-06 / EP-ORD-JOB-02). The order stays {@code CANCELLATION_REQUESTED}; from
     * {@code manualAttentionAfter} failed attempts on, it is flagged for a person.
     */
    public void recordCancellationFailure(String failure, Instant now, int manualAttentionAfter) {
        require(OrderStatus.CANCELLATION_REQUESTED, "record a cancellation failure");
        this.cancellationAttempts++;
        this.cancellationFailure = failure;
        this.cancellationLastAttemptAt = now;
        if (cancellationAttempts >= manualAttentionAfter) {
            this.needsManualAttention = true;
        }
    }

    /** True when the customer had paid before cancelling, so the saga must refund. */
    public boolean refundOwed() {
        return statusBeforeCancellation == OrderStatus.PAID || statusBeforeCancellation == OrderStatus.PENDING_SHIPPING;
    }

    /** True when the customer owns this order; another customer's order is answered like a missing one. */
    public boolean belongsTo(UUID customerId) {
        return this.customerId.equals(customerId);
    }

    private void require(OrderStatus expected, String action) {
        if (status != expected) {
            throw new InvalidOrderStateException(id, status, action);
        }
    }
}
