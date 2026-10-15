package ua.edu.ukma.springers.voltstore.delivery.entities;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Recipient;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidShipmentStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Delivery's only aggregate: the registration of one order's parcel with the carrier (one row per order, never
 * deleted). Every status change goes through the transition methods below, which enforce the lifecycle from the
 * spec; a forbidden change throws {@link InvalidShipmentStateException}.
 * <p>
 * A {@code CANCELLED} row without recipient data is a <i>tombstone</i>: the order was cancelled before its
 * {@code OrderPaid} was consumed, and the late event must not create a shipment.
 */
@Entity
@Table(name = "shipments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment {
    public static final int FAILURE_REASON_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // The natural key every caller uses; unique
    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ShipmentStatus status;

    // The carrier's waybill number; set once the carrier registered the parcel
    private String ttn;

    // Why the latest attempt (or the carrier cancellation) failed, in the stable format the fulfillment queue shows
    @Column(length = FAILURE_REASON_MAX_LENGTH)
    private String failureReason;

    // Recipient and order snapshot from OrderPaid; null only on a tombstone
    private String recipientName;
    private String recipientPhone;
    private String recipientAddress;
    private String carrier;
    private BigDecimal declaredValue;
    private String currency;

    // Items never change after creation. An element collection (not a child entity): they have no identity or
    // lifecycle of their own and are always read and written together with the shipment
    @ElementCollection
    @CollectionTable(name = "shipment_items", joinColumns = @JoinColumn(name = "shipment_id"))
    private List<ShipmentItem> items = new ArrayList<>();

    // The computed parcel; null until a TTN was generated
    private Integer totalWeightGrams;
    private Integer lengthCm;
    private Integer widthCm;
    private Integer heightCm;

    private Instant handedOverAt;

    // Technical columns of the TTN generation job: failed attempts so far, and when the next one is due
    // (null once the shipment left TTN_PENDING)
    @Column(nullable = false)
    private int attempts;

    private Instant nextAttemptAt;

    // Optimistic lock: state changes that follow a carrier call made outside a transaction must not overwrite a
    // change (e.g. a cancel) committed meanwhile
    @Version
    private long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * A new shipment from {@code OrderPaid}: waits for the TTN generation job, which picks it up immediately.
     * Lines with the same product are merged, so {@code (shipment_id, product_id)} stays unique.
     */
    public static Shipment pending(UUID orderId, Recipient recipient, String carrier, List<ShipmentItem> items,
                                   BigDecimal declaredValue, String currency, Instant now) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(declaredValue, "declaredValue must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A shipment needs at least one item");
        }
        if (declaredValue.signum() < 0) {
            throw new IllegalArgumentException("declaredValue must not be negative");
        }
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.status = ShipmentStatus.TTN_PENDING;
        shipment.recipientName = recipient.name();
        shipment.recipientPhone = recipient.phone();
        shipment.recipientAddress = recipient.address();
        shipment.carrier = carrier;
        shipment.items = mergeByProduct(items);
        shipment.declaredValue = declaredValue;
        shipment.currency = currency;
        shipment.attempts = 0;
        shipment.nextAttemptAt = now;
        return shipment;
    }

    /** The order was cancelled before its {@code OrderPaid} arrived: a terminal row without recipient data. */
    public static Shipment cancelledTombstone(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        Shipment shipment = new Shipment();
        shipment.orderId = orderId;
        shipment.status = ShipmentStatus.CANCELLED;
        return shipment;
    }

    public List<ShipmentItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isTombstone() {
        return status == ShipmentStatus.CANCELLED && recipientName == null;
    }

    public Recipient getRecipient() {
        return recipientName == null ? null : new Recipient(recipientName, recipientPhone, recipientAddress);
    }

    /** The carrier registered the parcel (automatic attempt from {@code TTN_PENDING}, manual retry from {@code TTN_FAILED}). */
    public void markGenerated(String ttn, Parcel parcel) {
        requireStatus(Set.of(ShipmentStatus.TTN_PENDING, ShipmentStatus.TTN_FAILED), "mark the TTN as generated");
        Objects.requireNonNull(ttn, "ttn must not be null");
        Objects.requireNonNull(parcel, "parcel must not be null");
        this.status = ShipmentStatus.TTN_GENERATED;
        this.ttn = ttn;
        this.totalWeightGrams = parcel.weightGrams();
        this.lengthCm = parcel.lengthCm();
        this.widthCm = parcel.widthCm();
        this.heightCm = parcel.heightCm();
        this.failureReason = null;
        this.nextAttemptAt = null;
    }

    /** A retryable attempt failed; the job tries again at {@code nextAttemptAt}. */
    public void recordFailedAttempt(String reason, Instant nextAttemptAt) {
        requireStatus(Set.of(ShipmentStatus.TTN_PENDING), "record a failed attempt");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        this.attempts++;
        this.failureReason = truncate(reason);
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * The attempt failed for good: retries ran out or cannot help (Tier 2). From {@code TTN_FAILED} (a failed manual
     * retry) the status stays and only the reason is replaced.
     */
    public void markFailed(String reason) {
        requireStatus(Set.of(ShipmentStatus.TTN_PENDING, ShipmentStatus.TTN_FAILED), "mark TTN generation as failed");
        this.attempts++;
        this.status = ShipmentStatus.TTN_FAILED;
        this.failureReason = truncate(reason);
        this.nextAttemptAt = null;
    }

    /**
     * Cancellation saga step. Allowed until the parcel is handed to the carrier; a generated TTN is kept, so the
     * caller can cancel it at the carrier afterwards.
     */
    public void cancel() {
        requireStatus(Set.of(ShipmentStatus.TTN_PENDING, ShipmentStatus.TTN_FAILED, ShipmentStatus.TTN_GENERATED),
                "cancel");
        this.status = ShipmentStatus.CANCELLED;
        this.nextAttemptAt = null;
    }

    /** The TTN of a cancelled shipment could not be cancelled at the carrier; kept for manual clean-up. */
    public void recordCarrierCancellationFailure(String error) {
        requireStatus(Set.of(ShipmentStatus.CANCELLED), "record a carrier cancellation failure");
        this.failureReason = truncate("carrier_cancellation_failed: " + error);
    }

    /** The courier collected the parcel ({@code OrderShipped}). */
    public void markInTransit(Instant handedOverAt) {
        requireStatus(Set.of(ShipmentStatus.TTN_GENERATED), "hand the parcel over");
        this.status = ShipmentStatus.IN_TRANSIT;
        this.handedOverAt = Objects.requireNonNull(handedOverAt, "handedOverAt must not be null");
    }

    /** The carrier reported the delivery. */
    public void markDelivered() {
        requireStatus(Set.of(ShipmentStatus.IN_TRANSIT), "mark as delivered");
        this.status = ShipmentStatus.DELIVERED;
    }

    private void requireStatus(Set<ShipmentStatus> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new InvalidShipmentStateException(orderId, status,
                    "Cannot " + action + " for a shipment in status " + status);
        }
    }

    private static String truncate(String reason) {
        return reason != null && reason.length() > FAILURE_REASON_MAX_LENGTH
                ? reason.substring(0, FAILURE_REASON_MAX_LENGTH) : reason;
    }

    private static List<ShipmentItem> mergeByProduct(List<ShipmentItem> items) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (ShipmentItem item : items) {
            quantities.merge(item.productId(), item.quantity(), Math::addExact);
        }
        List<ShipmentItem> merged = new ArrayList<>();
        quantities.forEach((productId, quantity) -> merged.add(new ShipmentItem(productId, quantity)));
        return merged;
    }
}
