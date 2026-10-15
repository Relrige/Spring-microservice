package ua.edu.ukma.springers.voltstore.payment.exceptions;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/** Only full refunds are supported: the amount and currency must equal the charge's. Answered with 422. */
@Getter
public class RefundAmountMismatchException extends RuntimeException {
    private final UUID orderId;

    public RefundAmountMismatchException(UUID orderId, BigDecimal chargedAmount, String chargedCurrency) {
        super("Only full refunds are supported: order " + orderId + " was charged "
                + chargedAmount.toPlainString() + " " + chargedCurrency);
        this.orderId = orderId;
    }
}
