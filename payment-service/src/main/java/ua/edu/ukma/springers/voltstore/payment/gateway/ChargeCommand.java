package ua.edu.ukma.springers.voltstore.payment.gateway;

import java.math.BigDecimal;

/** The card is printed masked by {@link PaymentCard#toString()}, so this record is safe to log. */
public record ChargeCommand(String idempotencyKey, BigDecimal amount, String currency, PaymentCard card) {
}
