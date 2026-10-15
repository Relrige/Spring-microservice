package ua.edu.ukma.springers.voltstore.payment.dto;

import java.util.UUID;

/**
 * Answer of a successful charge or refund. {@code transactionId} is our own {@code PaymentTransaction} ID; the
 * provider's ID stays inside Payment Service (Anti-Corruption Layer).
 */
public record PaymentResponse(UUID transactionId, String status) {

    public static PaymentResponse success(UUID transactionId) {
        return new PaymentResponse(transactionId, "SUCCESS");
    }
}
