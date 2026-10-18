package ua.edu.ukma.springers.voltstore.notification.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.notification.entities.OrderRecipient;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRecipientRepository extends JpaRepository<OrderRecipient, UUID> {

    /**
     * Creates or refreshes the order's recipient. Every event of an order carries the same address (Order's
     * snapshot at placement), so repeating it is harmless; {@code updated_at} marks the latest event for retention.
     */
    @Transactional
    @Modifying
    @NativeQuery("""
            INSERT INTO order_recipients (order_id, customer_email, updated_at)
            VALUES (:orderId, :customerEmail, :now)
            ON CONFLICT (order_id) DO UPDATE SET customer_email = EXCLUDED.customer_email,
                                                 updated_at = EXCLUDED.updated_at
            """)
    void upsertRecipient(UUID orderId, String customerEmail, Instant now);

    @Query("SELECT r.customerEmail FROM OrderRecipient r WHERE r.orderId = :orderId")
    Optional<String> findRecipient(UUID orderId);

    /** Retention: deletes recipients whose order had no event since {@code cutoff}. */
    @Transactional
    @Modifying
    @Query("DELETE FROM OrderRecipient r WHERE r.updatedAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
