package ua.edu.ukma.springers.voltstore.delivery.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
    Optional<Shipment> findByOrderId(UUID orderId);

    // The carrier identifies a parcel by its TTN (delivery report)
    Optional<Shipment> findByTtn(String ttn);

    // Batch status lookup for Order Service: one query for all IDs
    List<Shipment> findAllByOrderIdIn(Collection<UUID> orderIds);

    // Restart recovery: re-announce IN_TRANSIT parcels to the carrier
    List<Shipment> findAllByStatus(ShipmentStatus status);

    /**
     * The TTN job's claim: due {@code TTN_PENDING} shipments, oldest first, locked for the caller's transaction.
     * {@code SKIP LOCKED}: rows another job instance is claiming right now are skipped instead of waited for.
     */
    @NativeQuery("""
            SELECT * FROM shipments
            WHERE status = 'TTN_PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<Shipment> findDueForTtnAttemptSkipLocked(Instant now, int limit);

    /**
     * The claim's lease. A plain update on purpose: it does not increase the optimistic-lock version, so the version
     * seen at the claim still identifies "nobody changed the shipment" when the attempt's result is applied.
     */
    @Modifying
    @NativeQuery("UPDATE shipments SET next_attempt_at = :nextAttemptAt WHERE id IN (:ids)")
    int moveNextAttemptAt(Collection<UUID> ids, Instant nextAttemptAt);
}
