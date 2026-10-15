package ua.edu.ukma.springers.voltstore.payment.support;

import java.util.UUID;

/** JSON bodies of the charge and refund endpoints. */
public final class PaymentRequests {
    public static final String APPROVED_CARD = "4242424242424242";
    public static final String INSUFFICIENT_FUNDS_CARD = "4000000000000002";
    public static final String LOST_ANSWER_CARD = "4000000000005000";
    public static final String CVV = "987";

    private PaymentRequests() {
    }

    public static String charge(UUID orderId, String cardNumber) {
        return charge(orderId, "1499.90", cardNumber);
    }

    public static String charge(UUID orderId, String amount, String cardNumber) {
        return """
                {"orderId":"%s","amount":%s,"currency":"UAH",
                 "cardDetails":{"number":"%s","expiryMonth":12,"expiryYear":2035,"cvv":"%s"}}"""
                .formatted(orderId, amount, cardNumber, CVV);
    }

    public static String refund(UUID orderId, String amount) {
        return """
                {"orderId":"%s","amount":%s,"currency":"UAH"}""".formatted(orderId, amount);
    }
}
