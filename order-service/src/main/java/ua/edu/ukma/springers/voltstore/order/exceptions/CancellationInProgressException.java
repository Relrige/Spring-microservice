package ua.edu.ukma.springers.voltstore.order.exceptions;

import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;

import java.util.UUID;

/** A second cancellation request while the first is still running or being retried. Answered with 409. */
public class CancellationInProgressException extends InvalidOrderStateException {

    public CancellationInProgressException(UUID orderId) {
        super(orderId, OrderStatus.CANCELLATION_REQUESTED, "Cancellation of order " + orderId + " is already in progress",
                true);
    }
}
