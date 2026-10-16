package ua.edu.ukma.springers.voltstore.customer.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.customer.entities.Cart;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * Creates the customer's cart row unless it exists. Run before {@link #findByIdForUpdate} in the same transaction:
     * afterwards a row always exists, so the lock always has something to hold, and two parallel first writes cannot
     * collide on the primary key (the second insert does nothing).
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO carts (customer_id, created_at, updated_at)
            VALUES (:customerId, now(), now())
            ON CONFLICT (customer_id) DO NOTHING
            """, nativeQuery = true)
    void createIfMissing(@Param("customerId") UUID customerId);

    /**
     * Loads the cart with {@code SELECT ... FOR UPDATE}: other writers of the same cart wait until this transaction
     * ends. Readers are not blocked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Cart c WHERE c.customerId = :customerId")
    Optional<Cart> findByIdForUpdate(@Param("customerId") UUID customerId);
}
