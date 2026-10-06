package ua.edu.ukma.springers.voltstore.order.exceptions;

public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message) {
        super(message);
    }
}