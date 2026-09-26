package ua.edu.ukma.springers.voltstore.payment.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.payment.domain.entity.PaymentTransaction;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {
    Optional<PaymentTransaction> findByOrderId(UUID orderId);
    Optional<PaymentTransaction> findByExternalTransactionId(String externalTransactionId);
}