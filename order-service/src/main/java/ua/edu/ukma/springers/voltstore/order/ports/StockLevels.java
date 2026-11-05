package ua.edu.ukma.springers.voltstore.order.ports;

import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Inventory Service: the quantities available right now, for the checkout check (a hint; the reservation decides). */
public interface StockLevels {

    /**
     * @return available quantity per product; a product Inventory does not know has 0
     * @throws DependencyUnavailableException Inventory could not answer
     */
    Map<UUID, Integer> availableQuantities(Collection<UUID> productIds);
}
