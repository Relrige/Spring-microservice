package ua.edu.ukma.springers.voltstore.delivery.entities;

/**
 * Lifecycle of a shipment (see the Delivery spec, "Shipment lifecycle"). {@link #DELIVERED} and {@link #CANCELLED}
 * are terminal.
 */
public enum ShipmentStatus {
    // Created from OrderPaid; the TTN generation job is (re)trying to register it with the carrier
    TTN_PENDING,
    // Automatic attempts ran out or failed for good; waits for the worker's manual retry
    TTN_FAILED,
    // Registered with the carrier, the TTN is known; waits for the courier
    TTN_GENERATED,
    // Handed to the carrier (OrderShipped); only the carrier's delivery report moves it on
    IN_TRANSIT,
    DELIVERED,
    CANCELLED;

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
