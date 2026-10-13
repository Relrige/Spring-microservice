package ua.edu.ukma.springers.voltstore.catalog.exceptions;

/**
 * Inventory Service could not answer the readiness check (connection error, timeout, 5xx, or an unexpected
 * status such as 401/403). Mapped to 503; the cause is logged, never shown to the client.
 */
public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
