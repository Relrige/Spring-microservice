package ua.edu.ukma.springers.voltstore.inventory.exceptions;

/**
 * Catalog could not confirm whether a product exists (unreachable, timeout, {@code 5xx}, unexpected {@code 4xx}).
 * The message is for logs only; the client gets a fixed {@code 503} text.
 */
public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
