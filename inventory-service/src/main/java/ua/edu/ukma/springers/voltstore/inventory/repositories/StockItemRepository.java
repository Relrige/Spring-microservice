package ua.edu.ukma.springers.voltstore.inventory.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
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

    // EP-INV-JOB-01: the next IDs whose copy of the product data is missing, in a stable order, so the job can
    // continue after the last ID it processed (rows Catalog does not know stay null and must not be read again)
    @Query("select s.productId from StockItemEntity s where s.title is null and s.productId > :after "
            + "order by s.productId")
    List<UUID> findIdsWithoutTitleAfter(@Param("after") UUID after, Limit limit);

    /**
     * The only write of the copied product data (D8): creates the row if it is missing (zero quantities, no
     * packaging), otherwise applies the values only if they are newer ("newer version wins"). The version check and
     * the write are one statement, so concurrent consumers and replicas cannot interleave; quantities and packaging
     * are never touched.
     *
     * @return 1 if the row was created or its copy updated, 0 if the stored copy is as new or newer
     */
    @Modifying
    @Query(value = """
            INSERT INTO stock_items (product_id, title, product_status, product_version)
            VALUES (:id, :title, :status, :version)
            ON CONFLICT (product_id) DO UPDATE
               SET title = EXCLUDED.title, product_status = EXCLUDED.product_status,
                   product_version = EXCLUDED.product_version
             WHERE stock_items.product_version IS NULL OR stock_items.product_version < EXCLUDED.product_version
            """, nativeQuery = true)
    int upsertProductData(@Param("id") UUID id, @Param("title") String title, @Param("status") String status,
                          @Param("version") long version);
}