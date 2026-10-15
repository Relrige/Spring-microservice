package ua.edu.ukma.springers.voltstore.delivery.exceptions;

import lombok.Getter;

/**
 * Packaging could not be obtained from Inventory. {@code retryable} tells the TTN generation job whether a later
 * attempt may succeed (Inventory down, timeout, {@code 5xx}) or only a worker can fix it (no stock item, packaging
 * not configured). {@code reason} is the stable value stored as the shipment's failure reason.
 */
@Getter
public class PackagingUnavailableException extends RuntimeException {
    private final String reason;
    private final boolean retryable;

    public PackagingUnavailableException(String reason, boolean retryable, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
        this.retryable = retryable;
    }
}
