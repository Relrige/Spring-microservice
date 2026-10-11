package ua.edu.ukma.springers.voltstore.inventory.entities;

/**
 * Lifecycle of a stock reservation. Only {@code ACTIVE} holds stock; {@code RELEASED} (stock returned) and
 * {@code CONSUMED} (order shipped) are terminal.
 */
public enum ReservationStatus {
    ACTIVE,
    RELEASED,
    CONSUMED
}
