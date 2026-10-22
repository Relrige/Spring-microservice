package ua.edu.ukma.springers.voltstore.inventory.services;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * What Catalog told Inventory about one product: the values of the copied product data (D8), as received. They are
 * optional because Catalog sends {@code version} only from its task 29 on; the status is kept as text (tolerant
 * reader) and checked only when the copy is written.
 */
public record CatalogProductData(UUID productId, @Nullable String title, @Nullable String status,
                                 @Nullable Long version) {
}
