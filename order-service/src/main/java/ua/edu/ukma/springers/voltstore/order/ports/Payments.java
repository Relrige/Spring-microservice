package ua.edu.ukma.springers.voltstore.order.ports;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment Service. Never retried by Order: Payment already repeats uncertain calls with its own idempotency keys, and
 * a repeat of the same request by the customer is safe. So every outcome is a result, and the caller decides.
 */
public interface Payments {

    ChargeResult charge(UUID orderId, BigDecimal amount, String currency, PaymentCard card);

    RefundResult refund(UUID orderId, BigDecimal amount, String currency);

    /**
     * Card data on its way from the customer to Payment Service: kept in memory only, never stored or logged. A
     * record's generated {@code toString()} would print every field, so it is replaced by a masked form (as in
     * Payment Service).
     */
    record PaymentCard(String number, int expiryMonth, int expiryYear, String cvv) {

        @Override
        public String toString() {
            return "PaymentCard[**** **** **** " + number.substring(Math.max(0, number.length() - 4)) + "]";
        }
    }

    /** Payment's answer to a charge (EP-PAY-01), in the terms Order acts on. */
    sealed interface ChargeResult {
    }

    /** The card was charged; {@code transactionId} is Payment's own transaction ID. */
    record Paid(UUID transactionId) implements ChargeResult {
    }

    /** {@code 409} "Order already charged": an earlier attempt succeeded and its answer was lost. Counts as paid. */
    record AlreadyCharged() implements ChargeResult {
    }

    /** {@code 409} "Payment attempt in progress": the earlier attempt must be repeated with the same card. */
    record AttemptInProgress() implements ChargeResult {
    }

    /** {@code 402}: the card was declined; another card may be tried. */
    record Declined(String reason) implements ChargeResult {
    }

    /** No usable answer ({@code 5xx}, timeout, open circuit, unexpected status). Repeating the request is safe. */
    record ChargeUnavailable(String cause) implements ChargeResult {
    }

    /** Payment's answer to a full refund (EP-PAY-02). */
    sealed interface RefundResult {
    }

    record Refunded() implements RefundResult {
    }

    /** {@code 409}: refunded before (an earlier answer was lost). Counts as done. */
    record AlreadyRefunded() implements RefundResult {
    }

    /**
     * Not refunded now: rejected by the provider ({@code 502 refund_rejected}), Payment unreachable or slow, or an
     * unexpected answer. The cancellation saga tries again later.
     */
    record RefundFailed(String cause) implements RefundResult {
    }
}
