package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.PendingRefund;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.repositories.PendingRefundRepository;

import java.time.Clock;
import java.util.UUID;

/**
 * Refunds owed for a charge that succeeded after its order was cancelled or expired (EP-ORD-03 step 5). The debt is
 * stored first ({@link PendingRefund}), then paid; it is deleted only once Payment confirms the refund, and
 * {@code PendingRefundJob} repeats it until then. Repeating is safe: Payment refunds an order at most once and
 * answers a repeat with "already refunded".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwedRefunds {
    private final PendingRefundRepository pendingRefunds;
    private final Payments payments;
    private final TransactionOperations transactions;
    private final CancellationProperties properties;
    private final Clock clock;

    /** Records the refund of the order's total, then tries it once right away. */
    public void owe(OrderEntity order) {
        transactions.executeWithoutResult(tx -> {
            if (!pendingRefunds.existsById(order.getId())) {
                pendingRefunds.save(new PendingRefund(order.getId(), order.getTotalAmount(), order.getCurrency(),
                        clock.instant()));
            }
        });
        attempt(order.getId());
    }

    /** One refund attempt; the job calls it for every refund whose back-off has passed. */
    public void attempt(UUID orderId) {
        PendingRefund owed = pendingRefunds.findById(orderId).orElse(null);
        if (owed == null) {
            return;
        }
        Payments.RefundResult result = payments.refund(orderId, owed.getAmount(), owed.getCurrency());
        switch (result) {
            case Payments.Refunded refunded -> settled(orderId, "refunded");
            // An earlier attempt succeeded and its answer was lost
            case Payments.AlreadyRefunded alreadyRefunded -> settled(orderId, "already refunded");
            case Payments.RefundFailed failed -> failed(orderId, failed.cause());
        }
    }

    private void settled(UUID orderId, String how) {
        transactions.executeWithoutResult(tx -> pendingRefunds.deleteById(orderId));
        log.info("The charge of order {} is {}", orderId, how);
    }

    private void failed(UUID orderId, String cause) {
        transactions.executeWithoutResult(tx -> pendingRefunds.findById(orderId).ifPresent(owed -> {
            boolean flagged = owed.recordFailure("refund: " + cause, clock.instant(),
                    properties.manualAttentionAfter());
            log.warn("Refund of order {} failed (attempt {}): {}; it will be retried", orderId, owed.getAttempts(),
                    cause);
            if (flagged) {
                // The alert: once per refund, when it is flagged; retries continue
                log.error("Refund of order {} failed {} times and needs manual attention: {}", orderId,
                        owed.getAttempts(), cause);
            }
        }));
    }
}
