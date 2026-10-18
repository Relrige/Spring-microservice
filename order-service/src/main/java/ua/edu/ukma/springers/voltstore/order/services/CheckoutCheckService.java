package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;

import java.util.List;

/**
 * EP-ORD-01: can every product of the cart be ordered? One Catalog call answers both questions (status and stock
 * status, decision D2). Nothing is stored or reserved.
 */
@Service
@RequiredArgsConstructor
public class CheckoutCheckService {
    private final ProductCatalog catalog;

    /**
     * Returns normally when every product is active and in stock. Catalog's stock status has no quantities and lags
     * behind Inventory, so passing here does not promise the submission: the reservation may still refuse it.
     *
     * @throws IneligibleItemsException       listing every product that cannot be ordered
     * @throws DependencyUnavailableException Catalog could not answer; the check never passes without it
     */
    public void check(List<OrderItemRequest> items) {
        List<IneligibleItem> ineligible = ProductEligibility.forCheckout(items,
                ProductEligibility.byId(catalog.findProducts(ProductEligibility.productIds(items))));
        if (!ineligible.isEmpty()) {
            throw new IneligibleItemsException(ineligible);
        }
    }
}
