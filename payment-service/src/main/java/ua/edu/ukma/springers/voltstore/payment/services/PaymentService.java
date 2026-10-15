package ua.edu.ukma.springers.voltstore.payment.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.entities.PaymentTransactionEntity;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionType;
import ua.edu.ukma.springers.voltstore.payment.exceptions.NoSuccessfulChargeException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyChargedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyRefundedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayUnavailableException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundAmountMismatchException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundRejectedException;
import ua.edu.ukma.springers.voltstore.payment.gateway.ChargeCommand;
import ua.edu.ukma.springers.voltstore.payment.gateway.DeclineReason;
import ua.edu.ukma.springers.voltstore.payment.gateway.GatewayResult;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentCard;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.gateway.RefundCommand;
import ua.edu.ukma.springers.voltstore.payment.repositories.PaymentTransactionRepository;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionStatus.SUCCESS;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionType.CHARGE;
import static ua.edu.ukma.springers.voltstore.payment.entities.TransactionType.REFUND;

/**
 * EP-PAY-01 and EP-PAY-02. Double charges and double refunds are prevented by the gateway's idempotency keys, not by
 * locks: concurrent or repeated requests for one attempt send the same key, so the provider moves the money once.
 * <p>
 * No database transaction is open while the gateway is called (a slow provider would hold a connection and locks):
 * each repository call runs in its own short transaction, and the outcome is written after the gateway answered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    static final String SUCCESSFUL_CHARGE_INDEX = "uq_payment_transactions_successful_charge";
    static final String SUCCESSFUL_REFUND_INDEX = "uq_payment_transactions_successful_refund";

    private final PaymentTransactionRepository repository;
    private final PaymentGateway gateway;

    /**
     * Charges the order's card once.
     *
     * @return the ID of the successful {@code CHARGE} transaction
     * @throws OrderAlreadyChargedException       the order is already paid (also the losing side of a race)
     * @throws PaymentDeclinedException           the provider refused; the customer may retry with another card
     * @throws PaymentAttemptInProgressException  an earlier request of this attempt, with other details, is unresolved
     * @throws PaymentGatewayTimeoutException     no answer; a repeat of the same request is safe
     * @throws PaymentGatewayUnavailableException provider unreachable; a repeat of the same request is safe
     */
    public UUID charge(UUID orderId, BigDecimal amount, String currency, PaymentCard card) {
        if (repository.existsByOrderIdAndTypeAndStatus(orderId, CHARGE, SUCCESS)) {
            throw new OrderAlreadyChargedException(orderId);
        }
        String key = chargeKey(orderId);
        GatewayResult result = callGateway(CHARGE, orderId, amount, currency, key,
                () -> gateway.charge(new ChargeCommand(key, amount, currency, card)));

        return switch (result) {
            case GatewayResult.Approved approved -> saveSuccess(CHARGE, orderId, amount, currency,
                    approved.gatewayTransactionId(), key, SUCCESSFUL_CHARGE_INDEX,
                    () -> new OrderAlreadyChargedException(orderId));
            case GatewayResult.Declined declined -> {
                FailureReason reason = toFailureReason(declined.reason());
                repository.saveAndFlush(PaymentTransactionEntity.failed(CHARGE, orderId, amount, currency, reason, key));
                throw new PaymentDeclinedException(reason);
            }
            case GatewayResult.IdempotencyKeyMismatch ignored -> throw new PaymentAttemptInProgressException(orderId);
        };
    }

    /**
     * Refunds the order's successful charge in full, once.
     *
     * @return the ID of the successful {@code REFUND} transaction
     * @throws NoSuccessfulChargeException    the order was never charged
     * @throws OrderAlreadyRefundedException  the order is already refunded (also the losing side of a race)
     * @throws RefundAmountMismatchException  amount or currency differ from the charge (no partial refunds)
     * @throws RefundRejectedException        the provider refused the refund
     */
    public UUID refund(UUID orderId, BigDecimal amount, String currency) {
        PaymentTransactionEntity charge = repository.findByOrderIdAndTypeAndStatus(orderId, CHARGE, SUCCESS)
                .orElseThrow(() -> new NoSuccessfulChargeException(orderId));
        if (repository.existsByOrderIdAndTypeAndStatus(orderId, REFUND, SUCCESS)) {
            throw new OrderAlreadyRefundedException(orderId);
        }
        if (charge.getAmount().compareTo(amount) != 0 || !charge.getCurrency().equals(currency)) {
            throw new RefundAmountMismatchException(orderId, charge.getAmount(), charge.getCurrency());
        }
        // One full refund per order, so one key: every repeat of the refund is the same request to the provider
        String key = "refund:" + orderId;
        GatewayResult result = callGateway(REFUND, orderId, amount, currency, key,
                () -> gateway.refund(new RefundCommand(key, charge.getGatewayTransactionId(), amount, currency)));

        return switch (result) {
            case GatewayResult.Approved approved -> saveSuccess(REFUND, orderId, amount, currency,
                    approved.gatewayTransactionId(), key, SUCCESSFUL_REFUND_INDEX,
                    () -> new OrderAlreadyRefundedException(orderId));
            case GatewayResult.Declined declined -> {
                repository.saveAndFlush(PaymentTransactionEntity.failed(REFUND, orderId, amount, currency,
                        toFailureReason(declined.reason()), key));
                throw new RefundRejectedException(orderId);
            }
            case GatewayResult.IdempotencyKeyMismatch ignored -> throw new PaymentAttemptInProgressException(orderId);
        };
    }

    /**
     * {@code charge:<orderId>:<attempt>}. A definitive decline ends an attempt, so the next request gets a new key and
     * the customer can try another card. An uncertain outcome (timeout, provider unreachable) does not: the next
     * request reuses the key, and the provider either replays the stored result or processes it for the first time.
     * Requests running at the same time read the same rows and so compute the same key.
     */
    String chargeKey(UUID orderId) {
        long endedAttempts = repository.countAttemptsEndedBy(orderId, CHARGE, FailureReason.definitiveReasons());
        return "charge:" + orderId + ":" + (endedAttempts + 1);
    }

    /** Records an uncertain outcome before passing it on, so the history shows the attempt and its key. */
    private GatewayResult callGateway(TransactionType type, UUID orderId, BigDecimal amount, String currency,
                                      String key, Supplier<GatewayResult> call) {
        try {
            return call.get();
        } catch (PaymentGatewayTimeoutException e) {
            repository.saveAndFlush(PaymentTransactionEntity.failed(type, orderId, amount, currency,
                    FailureReason.GATEWAY_TIMEOUT, key));
            throw e;
        } catch (PaymentGatewayUnavailableException e) {
            repository.saveAndFlush(PaymentTransactionEntity.failed(type, orderId, amount, currency,
                    FailureReason.GATEWAY_UNAVAILABLE, key));
            throw e;
        }
    }

    /**
     * Two concurrent requests with one key both get the provider's single approval and both insert a success; the
     * partial unique index lets one through. The other is answered as "already done", which is true: the money moved
     * once. Only that index is translated; any other constraint violation is a bug and stays a 500.
     */
    private UUID saveSuccess(TransactionType type, UUID orderId, BigDecimal amount, String currency,
                             String gatewayTransactionId, String key, String uniqueIndex,
                             Supplier<RuntimeException> alreadyDone) {
        try {
            return repository.saveAndFlush(PaymentTransactionEntity.succeeded(type, orderId, amount, currency,
                    gatewayTransactionId, key)).getId();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, uniqueIndex)) {
                log.info("Concurrent {} of order {} with key {} was already recorded", type, orderId, key);
                throw alreadyDone.get();
            }
            throw e;
        }
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static FailureReason toFailureReason(DeclineReason reason) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS -> FailureReason.INSUFFICIENT_FUNDS;
            case CARD_DECLINED -> FailureReason.CARD_DECLINED;
            case REFUND_REJECTED -> FailureReason.REFUND_REJECTED;
        };
    }
}
