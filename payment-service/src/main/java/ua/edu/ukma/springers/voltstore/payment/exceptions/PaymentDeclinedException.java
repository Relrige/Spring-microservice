package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;

/** The gateway answered "no" (for example insufficient funds). A business outcome, answered with 402. */
@Getter
public class PaymentDeclinedException extends RuntimeException {
    private final FailureReason reason;

    public PaymentDeclinedException(FailureReason reason) {
        super("Payment declined: " + reason.code());
        this.reason = reason;
    }
}
