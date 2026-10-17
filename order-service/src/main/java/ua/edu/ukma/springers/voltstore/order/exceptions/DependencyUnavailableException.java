package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

/**
 * Catalog, Inventory, Payment or Delivery is down, too slow, or answered unexpectedly. The cause is only logged; the
 * client gets a fixed text. Answered with 503.
 */
@Getter
public class DependencyUnavailableException extends RuntimeException {
    private final String service;

    public DependencyUnavailableException(String service, String message, Throwable cause) {
        super(service + " unavailable: " + message, cause);
        this.service = service;
    }
}
