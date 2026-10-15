package ua.edu.ukma.springers.voltstore.delivery.services;

import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;

import java.util.List;

/**
 * What Delivery needs from Inventory: the packaging of a shipment's products. Hides the HTTP client from the
 * services (implemented by {@code clients.InventoryPackagingProvider}).
 */
public interface PackagingProvider {
    /**
     * @return one {@link ItemPackaging} per item, in item order
     * @throws ua.edu.ukma.springers.voltstore.delivery.exceptions.PackagingUnavailableException if the packaging of
     *         any product cannot be obtained
     */
    List<ItemPackaging> packagingFor(List<ShipmentItem> items);
}
