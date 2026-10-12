package ua.edu.ukma.springers.voltstore.inventory.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // For release: a concurrent call for the same order waits on these row locks. Once the first call commits,
    // PostgreSQL re-checks the WHERE clause on the changed rows, so the waiting call no longer sees them.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockItemReservationEntity r where r.orderId = :orderId and r.status = :status "
            + "order by r.productId")
    List<StockItemReservationEntity> findByOrderIdAndStatusForUpdate(@Param("orderId") UUID orderId,
                                                                     @Param("status") ReservationStatus status);
}
