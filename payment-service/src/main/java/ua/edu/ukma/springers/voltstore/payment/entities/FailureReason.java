package ua.edu.ukma.springers.voltstore.payment.entities;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Why a gateway request did not succeed. A definitive reason means the gateway answered "no" and took no money; an
 * uncertain one means there was no answer, so the money may have been taken. The charge attempt counter (task 06)
 * starts a new idempotency key only after a definitive decline; after an uncertain one the same key is reused.
 */
public enum FailureReason {
    INSUFFICIENT_FUNDS(true),
    CARD_DECLINED(true),
    REFUND_REJECTED(true),
    GATEWAY_TIMEOUT(false),
    GATEWAY_UNAVAILABLE(false);

    private final boolean definitive;

    FailureReason(boolean definitive) {
        this.definitive = definitive;
    }

    public boolean isDefinitive() {
        return definitive;
    }

    /** The value used in API responses, for example {@code insufficient_funds}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static List<FailureReason> definitiveReasons() {
        return Arrays.stream(values()).filter(FailureReason::isDefinitive).toList();
    }
}
