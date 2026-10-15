package ua.edu.ukma.springers.voltstore.payment.gateway;

/** The provider's definitive answer. Uncertain outcomes (no answer) are exceptions, see {@link PaymentGateway}. */
public sealed interface GatewayResult {

    /** The money moved; the ID identifies the charge or refund at the provider. */
    record Approved(String gatewayTransactionId) implements GatewayResult {
    }

    /** The provider refused; nothing moved. */
    record Declined(DeclineReason reason) implements GatewayResult {
    }

    /**
     * The key was used before with different parameters (for example another card), so the provider refused to
     * process it. The earlier request's outcome stands.
     */
    record IdempotencyKeyMismatch() implements GatewayResult {
    }
}
