package ua.edu.ukma.springers.voltstore.payment.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.entities.PaymentTransactionEntity;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionStatus;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionType;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {
    // Both use the (order_id, type, status) index
    boolean existsByOrderIdAndTypeAndStatus(UUID orderId, TransactionType type, TransactionStatus status);

    // At most one row for SUCCESS (partial unique index); used to find the charge a refund belongs to
    Optional<PaymentTransactionEntity> findByOrderIdAndTypeAndStatus(UUID orderId, TransactionType type,
                                                                    TransactionStatus status);

    /**
     * Number of attempts of this order that ended with one of the given reasons, counted by distinct idempotency key:
     * two concurrent requests of one attempt share a key, so their rows count once. Used for the charge attempt
     * number (task 06), with the definitive reasons only.
     */
    @Query("select count(distinct t.idempotencyKey) from PaymentTransactionEntity t "
            + "where t.orderId = :orderId and t.type = :type and t.failureReason in :reasons")
    long countAttemptsEndedBy(@Param("orderId") UUID orderId, @Param("type") TransactionType type,
                              @Param("reasons") Collection<FailureReason> reasons);
}
