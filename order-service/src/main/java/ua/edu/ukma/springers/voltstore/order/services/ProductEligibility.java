package ua.edu.ukma.springers.voltstore.order.services;

import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which requested products cannot be ordered, judged from one Catalog answer. Shared by the soft check (EP-ORD-01),
 * which also looks at Catalog's stock status, and the submission (EP-ORD-02), where Inventory's reservation is the
 * stock check.
 */
final class ProductEligibility {

    private ProductEligibility() {
    }

    static List<UUID> productIds(Collection<OrderItemRequest> items) {
        return items.stream().map(OrderItemRequest::productId).toList();
    }

    static Map<UUID, CatalogProduct> byId(Collection<CatalogProduct> products) {
        return products.stream().collect(Collectors.toMap(CatalogProduct::productId, Function.identity(),
                (first, second) -> first));
    }

    /** Missing or not active → {@code NOT_ACTIVE}; {@code OUT_OF_STOCK} → {@code INSUFFICIENT_STOCK}. */
    static List<IneligibleItem> forCheckout(List<OrderItemRequest> items, Map<UUID, CatalogProduct> products) {
        return ineligible(items, products, true);
    }

    /** Missing or not active → {@code NOT_ACTIVE}. Stock is left to the reservation, which knows the quantities. */
    static List<IneligibleItem> forPlacement(List<OrderItemRequest> items, Map<UUID, CatalogProduct> products) {
        return ineligible(items, products, false);
    }

    // In request order, each product at most once: a product both inactive and out of stock is reported as inactive
    private static List<IneligibleItem> ineligible(List<OrderItemRequest> items, Map<UUID, CatalogProduct> products,
                                                   boolean checkStock) {
        List<IneligibleItem> ineligible = new ArrayList<>();
        for (OrderItemRequest item : items) {
            CatalogProduct product = products.get(item.productId());
            if (product == null || !product.isActive()) {
                ineligible.add(new IneligibleItem(item.productId(), IneligibleItem.Reason.NOT_ACTIVE));
            } else if (checkStock && product.stockStatus() == CatalogProduct.StockStatus.OUT_OF_STOCK) {
                // A null stock status (Catalog without task 24) is not a reason to refuse: the reservation decides
                ineligible.add(new IneligibleItem(item.productId(), IneligibleItem.Reason.INSUFFICIENT_STOCK));
            }
        }
        return ineligible;
    }
}
