package ua.edu.ukma.springers.voltstore.payment.gateway;

import java.math.BigDecimal;

/** Refunds the charge the provider knows as {@code gatewayTransactionId}; only full refunds are made. */
public record RefundCommand(String idempotencyKey, String gatewayTransactionId, BigDecimal amount, String currency) {
}
