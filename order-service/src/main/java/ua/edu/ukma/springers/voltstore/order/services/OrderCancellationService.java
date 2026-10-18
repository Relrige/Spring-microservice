package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.dto.CancellationResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.exceptions.CancellationInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;

import java.time.Clock;
import java.util.UUID;

/**
 * EP-ORD-06: the customer cancels their own order before it is handed to the courier. The request is recorded
 * ({@code CANCELLATION_REQUESTED} and where it started) and committed <b>before</b> the first remote call, so the
 * job can finish the saga even if this instance dies halfway.
 */
@Service
@RequiredArgsConstructor
public class OrderCancellationService {
    // Each lost race re-reads the order; three are more than a payment and a cancellation of one order can cause
    private static final int MAX_REQUEST_ATTEMPTS = 3;

    private final OrderAccess orderAccess;
    private final OrderRepository orders;
    private final CancellationSaga saga;
    private final TransactionOperations transactions;
    private final Clock clock;

    /**
     * @return {@code CANCELLED} when the saga finished, {@code CANCELLATION_REQUESTED} when a step is still pending
     * @throws OrderNotFoundException           not the caller's order, or none (404)
     * @throws CancellationInProgressException  a cancellation is already running or being retried (409)
     * @throws InvalidOrderStateException       shipped, delivered, cancelled or expired (409)
     */
    public CancellationResponse cancel(UUID orderId, AuthenticatedUser customer) {
        checkCancellable(orderAccess.loadOwned(orderId, customer));
        requestCancellation(orderId);
        return new CancellationResponse(orderId, saga.run(orderId));
    }

    // Check-and-set: re-read in the transaction, and the version catches a payment committed in between, which
    // then decides whether a refund is owed
    private void requestCancellation(UUID orderId) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactions.executeWithoutResult(tx -> {
                    OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
                    checkCancellable(order);
                    order.requestCancellation(clock.instant());
                    orders.saveAndFlush(order);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == MAX_REQUEST_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static void checkCancellable(OrderEntity order) {
        if (order.getStatus() == OrderStatus.CANCELLATION_REQUESTED) {
            throw new CancellationInProgressException(order.getId());
        }
        if (!OrderStatus.CANCELLABLE.contains(order.getStatus())) {
            throw new InvalidOrderStateException(order.getId(), order.getStatus(), "be cancelled");
        }
    }
}
