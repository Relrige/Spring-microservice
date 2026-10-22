package ua.edu.ukma.springers.voltstore.order.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.order.entities.IdempotencyKey;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKey.Id> {

    /**
     * Claims the key for a new request: {@code 1} when this request owns it, {@code 0} when a row already exists. A
     * plain insert would fail with a constraint violation at flush and roll back the caller's transaction.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (customer_id, idempotency_key, request_hash, status, created_at) "
            + "VALUES (:customerId, :key, :requestHash, 'IN_PROGRESS', :now) "
            + "ON CONFLICT (customer_id, idempotency_key) DO NOTHING", nativeQuery = true)
    int claim(@Param("customerId") UUID customerId, @Param("key") UUID key, @Param("requestHash") String requestHash,
              @Param("now") Instant now);

    /** Remembers the stored order; {@code 0} when the claim is gone (purged as stale). */
    @Modifying
    @Query(value = "UPDATE idempotency_keys SET status = 'COMPLETED', order_id = :orderId "
            + "WHERE customer_id = :customerId AND idempotency_key = :key AND status = 'IN_PROGRESS'",
            nativeQuery = true)
    int complete(@Param("customerId") UUID customerId, @Param("key") UUID key, @Param("orderId") UUID orderId);

    /** Forgets a claim whose request failed, so a retry with the same key runs again (D6). */
    @Modifying
    @Query(value = "DELETE FROM idempotency_keys "
            + "WHERE customer_id = :customerId AND idempotency_key = :key AND status = 'IN_PROGRESS'",
            nativeQuery = true)
    int release(@Param("customerId") UUID customerId, @Param("key") UUID key);
}
