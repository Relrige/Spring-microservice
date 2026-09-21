package ua.edu.ukma.springers.voltstore.payment.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.payment.domain.entity.PaymentTransaction;
import ua.edu.ukma.springers.voltstore.payment.repositories.PaymentTransactionRepository;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentTransactionRepository paymentRepository;

    @Transactional
    public PaymentTransaction initiatePayment(UUID orderId, BigDecimal amount) {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setOrderId(orderId);
        transaction.setAmount(amount);
        transaction.setStatus(PaymentTransaction.PaymentStatus.PENDING);
        return paymentRepository.save(transaction);
    }

    @Transactional
    public PaymentTransaction completePayment(UUID orderId, String externalId, boolean success) {
        PaymentTransaction transaction = paymentRepository.findByOrderId(orderId).orElseThrow();
        transaction.setExternalTransactionId(externalId);
        transaction.setStatus(success ? PaymentTransaction.PaymentStatus.SUCCESS : PaymentTransaction.PaymentStatus.FAILED);
        return paymentRepository.save(transaction);
    }

    @Transactional
    public PaymentTransaction refundPayment(UUID orderId) {
        PaymentTransaction transaction = paymentRepository.findByOrderId(orderId).orElseThrow();
        transaction.setStatus(PaymentTransaction.PaymentStatus.REFUNDED);
        return paymentRepository.save(transaction);
    }
}