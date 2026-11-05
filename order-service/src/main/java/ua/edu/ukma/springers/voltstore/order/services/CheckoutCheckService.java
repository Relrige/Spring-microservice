package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.ports.StockLevels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * EP-ORD-01: can every product of the cart be ordered? Catalog answers status and stock status (decision D2);
 * Inventory then compares the requested quantities with what is available (gRPC GetStockLevels, task
 * grpc-order-inventory). Nothing is stored or reserved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutCheckService {
    private final ProductCatalog catalog;
    private final StockLevels stock;

    /**
     * Returns normally when every product is active, in stock and available in the requested quantity. Passing here
     * does not promise the submission: the reservation may still refuse it.
     *
     * @throws IneligibleItemsException       listing every product that cannot be ordered
     * @throws DependencyUnavailableException Catalog could not answer; the check never passes without it
     */
    public void check(List<OrderItemRequest> items) {
        List<IneligibleItem> ineligible = new ArrayList<>(ProductEligibility.forCheckout(items,
                ProductEligibility.byId(catalog.findProducts(ProductEligibility.productIds(items)))));
        ineligible.addAll(shortInInventory(items, ineligible));
        if (!ineligible.isEmpty()) {
            throw new IneligibleItemsException(ineligible);
        }
    }

    /**
     * The products Catalog let through whose requested quantity exceeds Inventory's available quantity. A hint
     * like the rest of the check: if Inventory cannot answer, Catalog's result stands and the reservation decides.
     */
    private List<IneligibleItem> shortInInventory(List<OrderItemRequest> items, List<IneligibleItem> ineligible) {
        Set<UUID> already = ineligible.stream().map(IneligibleItem::productId).collect(Collectors.toSet());
        List<OrderItemRequest> toCheck = items.stream().filter(item -> !already.contains(item.productId())).toList();
        if (toCheck.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> available;
        try {
            available = stock.availableQuantities(ProductEligibility.productIds(toCheck));
        } catch (DependencyUnavailableException e) {
            log.warn("Checkout check without Inventory's quantities: {}", e.getMessage());
            return List.of();
        }
        return toCheck.stream()
                .filter(item -> item.quantity() > available.getOrDefault(item.productId(), 0))
                .map(item -> new IneligibleItem(item.productId(), IneligibleItem.Reason.INSUFFICIENT_STOCK))
                .toList();
    }
}
