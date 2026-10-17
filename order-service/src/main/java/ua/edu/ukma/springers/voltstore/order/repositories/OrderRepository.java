package ua.edu.ukma.springers.voltstore.order.repositories;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    /** Ownership in one query: another customer's order is simply not found (answered like a missing one). */
    @EntityGraph(attributePaths = "items")
    Optional<OrderEntity> findByIdAndCustomerId(UUID id, UUID customerId);

    // Order history; the caller passes the sort (placedAt DESC), served by idx_orders_customer_placed
    Page<OrderEntity> findByCustomerId(UUID customerId, Pageable pageable);

    /** Fulfillment queue: paid orders waiting for dispatch, oldest payment first. */
    @EntityGraph(attributePaths = "items")
    List<OrderEntity> findByStatusInOrderByPaidAtAsc(Collection<OrderStatus> statuses);

    /**
     * Payment-window expiry candidates. {@code expiredBefore} is {@code now - grace}, so an order whose payment may
     * still be running is not picked.
     */
    @Query("select o from OrderEntity o where o.status = ua.edu.ukma.springers.voltstore.order.entities.OrderStatus"
            + ".PENDING_PAYMENT and o.reservationExpiresAt <= :expiredBefore order by o.reservationExpiresAt")
    List<OrderEntity> findExpired(@Param("expiredBefore") Instant expiredBefore, Limit limit);

    /** Interrupted cancellations, the ones waiting longest first; the job applies the back-off per order. */
    @Query("select o from OrderEntity o where o.status = ua.edu.ukma.springers.voltstore.order.entities.OrderStatus"
            + ".CANCELLATION_REQUESTED order by o.cancellationLastAttemptAt asc nulls first")
    List<OrderEntity> findInterruptedCancellations(Limit limit);
}
