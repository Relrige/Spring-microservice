package ua.edu.ukma.springers.voltstore.inventory.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockItemRepository extends JpaRepository<StockItemEntity, UUID>, JpaSpecificationExecutor<StockItemEntity> {
    // For order's products reservation.
    // Locking by SELECT FOR UPDATE to prevent concurrent requests trying to reserve the same product.
    // E.g., availableQuantity = 5, both requests need 5 items, so only one should succeed.
    // Order by product_id to prevent deadlock.
    // E.g., both requests need products A and B.
    // First request locks product A then B, second request locks product B then A,
    // thus deadlock.
    // With ordering by product id locking will be always in the same sequence.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItemEntity s where s.productId in :ids order by s.productId")
    List<StockItemEntity> findAllByIdsForUpdate(@Param("ids") Collection<UUID> ids);

    // Same as previous but for one entity.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItemEntity s where s.productId = :id")
    Optional<StockItemEntity> findByIdForUpdate(@Param("id") UUID id);

    // Idempotent create with column defaults (zero quantities, no packaging).
    // Unlike save(), concurrent first-time callers do not fail with a duplicate key:
    // the second insert waits for the first transaction and then does nothing.
    @Modifying
    @Query(value = "INSERT INTO stock_items (product_id) VALUES (:id) ON CONFLICT (product_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id);
}