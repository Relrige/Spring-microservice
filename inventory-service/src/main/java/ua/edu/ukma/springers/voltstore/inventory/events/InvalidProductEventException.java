package ua.edu.ukma.springers.voltstore.inventory.events;

/**
 * A product event that converted, but lacks a field its type requires (e.g. a {@code ProductUpdated} without
 * {@code version}). Retrying cannot fix it, so {@link ProductEventsListener} sends it to the DLT at once, like a
 * payload that cannot be converted.
 */
public class InvalidProductEventException extends RuntimeException {
    public InvalidProductEventException(String message) {
        super(message);
    }
}
