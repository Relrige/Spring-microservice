package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.dto.CardDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PaidOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderPaidEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentWindowExpiredException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * EP-ORD-03: the customer pays their own order inside the payment window.
 * <p>
 * The charge can take seconds, so it runs <b>without</b> a database transaction, on an order read before it. While it
 * runs, the customer may cancel the order or the window may expire. Recording the payment is therefore a
 * check-and-set in its own transaction: the order is read again, and only an order still {@code PENDING_PAYMENT}
 * becomes {@code PAID}; the {@code @Version} column catches a change between that read and the commit. An order that
 * changed in the meantime has been charged for nothing, so the money is refunded at once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService {
    /** A paid order seen on re-reading: a parallel request of the same customer recorded the same charge. */
    private static final Set<OrderStatus> ALREADY_PAID =
            Set.of(OrderStatus.PAID, OrderStatus.PENDING_SHIPPING, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    // Each lost race re-reads the order; three are more than two requests for one order can cause
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final OrderAccess orderAccess;
    private final OrderRepository orders;
    private final Payments payments;
    private final Outbox outbox;
    private final TransactionOperations transactions;
    private final Clock clock;

    /**
     * @throws OrderNotFoundException            not the caller's order, or none (404)
     * @throws InvalidOrderStateException        not awaiting payment, or changed during the charge (refunded) (409)
     * @throws PaymentWindowExpiredException     the window has elapsed; nothing was charged (409)
     * @throws PaymentDeclinedException          the card was declined; the order is still payable (402)
     * @throws PaymentAttemptInProgressException an earlier attempt must be repeated with the same card (409)
     * @throws DependencyUnavailableException    no usable answer from Payment; repeating with the same card is safe
     */
    public PaidOrderResponse pay(UUID orderId, AuthenticatedUser customer, CardDetailsRequest card) {
        OrderEntity order = orderAccess.loadOwned(orderId, customer);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new InvalidOrderStateException(orderId, order.getStatus(), "be paid");
        }
        if (!order.getReservationExpiresAt().isAfter(clock.instant())) {
            throw new PaymentWindowExpiredException(orderId);
        }

        Payments.ChargeResult result = payments.charge(orderId, order.getTotalAmount(), order.getCurrency(),
                card.toPaymentCard());
        return switch (result) {
            case Payments.Paid paid -> recordPayment(order);
            // An earlier attempt succeeded and its answer was lost: the same money, not a second charge
            case Payments.AlreadyCharged alreadyCharged -> recordPayment(order);
            // The window is not extended: the customer may try another card while it lasts
            case Payments.Declined declined -> throw new PaymentDeclinedException(declined.reason());
            case Payments.AttemptInProgress inProgress -> throw new PaymentAttemptInProgressException(orderId);
            case Payments.ChargeUnavailable unavailable ->
                    throw new DependencyUnavailableException("Payment", unavailable.cause(), null);
        };
    }

    private PaidOrderResponse recordPayment(OrderEntity charged) {
        for (int attempt = 1; ; attempt++) {
            try {
                OrderStatus status = transactions.execute(tx -> markPaid(charged.getId()));
                if (ALREADY_PAID.contains(status)) {
                    return new PaidOrderResponse(charged.getId(), status);
                }
                // Cancelled or expired while the card was being charged
                refundAfterLostRace(charged, status);
                throw new InvalidOrderStateException(charged.getId(), status, "be paid");
            } catch (OptimisticLockingFailureException e) {
                // Changed between the re-read and the commit: read it again and decide again
                if (attempt == MAX_RECORD_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /** Returns the status after the attempt: {@code PAID} (now or before), or the status that prevented it. */
    private OrderStatus markPaid(UUID orderId) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return order.getStatus();
        }
        // Even if the window has just passed: the charge started inside it, and the expiry job waits for a grace
        // period longer than a charge can take
        Instant now = clock.instant();
        order.markPaid(now);
        orders.saveAndFlush(order);
        outbox.append(OrderPaidEvent.of(order, now));
        return order.getStatus();
    }

    private void refundAfterLostRace(OrderEntity order, OrderStatus status) {
        log.warn("Order {} became {} while its card was being charged; refunding the charge", order.getId(), status);
        Payments.RefundResult refund = payments.refund(order.getId(), order.getTotalAmount(), order.getCurrency());
        switch (refund) {
            case Payments.Refunded refunded -> log.info("Refunded the charge of order {}", order.getId());
            case Payments.AlreadyRefunded alreadyRefunded ->
                    log.info("The charge of order {} was refunded before", order.getId());
            // Nothing else will refund it: the order is no longer one the cancellation saga refunds
            case Payments.RefundFailed failed -> log.error("The charge of order {} could not be refunded ({}); "
                    + "refund it by hand", order.getId(), failed.cause());
        }
    }
}
