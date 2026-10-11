package ua.edu.ukma.springers.voltstore.inventory.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface StockItemReservationRepository extends JpaRepository<StockItemReservationEntity, UUID> {
    // Both use the (order_id, status) index
    List<StockItemReservationEntity> findByOrderIdAndStatus(UUID orderId, ReservationStatus status);

    boolean existsByOrderIdAndStatus(UUID orderId, ReservationStatus status);
}
