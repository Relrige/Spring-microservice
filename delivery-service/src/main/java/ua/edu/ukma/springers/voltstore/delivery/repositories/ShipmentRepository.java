package ua.edu.ukma.springers.voltstore.delivery.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;

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
}
