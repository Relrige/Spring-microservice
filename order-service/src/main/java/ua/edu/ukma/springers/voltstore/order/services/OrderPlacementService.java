package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutProperties;
import ua.edu.ukma.springers.voltstore.order.dto.DeliveryDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.events.OrderPlacedEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EP-ORD-02: turns a cart into an order. The steps run in this order so that stock is never left reserved without
 * an order (only orders expire, so nothing else would ever release it):
 * <ol>
 *   <li>Catalog: current titles, prices and statuses. Nothing is reserved yet, so a refusal costs nothing.</li>
 *   <li>Inventory: reserve all items under the new order's ID, all or nothing.</li>
 *   <li>One local transaction: the order with its snapshots, and {@code OrderPlaced} in the outbox.</li>
 * </ol>
 * No database transaction is open during the two remote calls. If anything fails after the reservation may exist,
 * the reservation is released (best effort; release is idempotent and does nothing when nothing is reserved).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPlacementService {
    static final String CURRENCY = "UAH";

    private final ProductCatalog catalog;
    private final StockReservations stock;
    private final OrderRepository orders;
    private final Outbox outbox;
    private final TransactionOperations transactions;
    private final CheckoutProperties properties;
    private final Clock clock;

    /**
     * @throws MissingCustomerEmailException  the gateway sent no email (answered 401)
     * @throws IneligibleItemsException       a product is unknown or not active; nothing was reserved
     * @throws InsufficientStockException     Inventory could not reserve every item; nothing was reserved
     * @throws DependencyUnavailableException Catalog or Inventory could not answer; no order was created
     */
    public PlacedOrderResponse place(AuthenticatedUser customer, PlaceOrderRequest request) {
        if (customer.email() == null) {
            throw new MissingCustomerEmailException();
        }
        // Assigned here, because Inventory reserves under it before the order exists
        UUID orderId = UUID.randomUUID();

        Map<UUID, CatalogProduct> products = ProductEligibility.byId(
                catalog.findProducts(ProductEligibility.productIds(request.items())));
        List<IneligibleItem> ineligible = ProductEligibility.forPlacement(request.items(), products);
        if (!ineligible.isEmpty()) {
            throw new IneligibleItemsException(ineligible);
        }

        reserve(orderId, request.items());

        try {
            OrderEntity order = transactions.execute(status -> store(orderId, customer, request, products));
            return PlacedOrderResponse.of(order);
        } catch (RuntimeException e) {
            // The cause is logged with its stack trace by the exception handler (500)
            log.error("Order {} could not be stored after its stock was reserved; releasing the reservation",
                    orderId);
            releaseQuietly(orderId);
            throw e;
        }
    }

    private void reserve(UUID orderId, List<OrderItemRequest> items) {
        StockReservations.ReservationResult result;
        try {
            result = stock.reserve(orderId, items.stream()
                    .map(item -> new ReservationItem(item.productId(), item.quantity()))
                    .toList());
        } catch (DependencyUnavailableException e) {
            // A call that timed out may have reserved the stock anyway
            releaseQuietly(orderId);
            throw e;
        }
        switch (result) {
            // Also an earlier call for this order whose answer was lost (409)
            case StockReservations.Reserved reserved -> {
            }
            case StockReservations.Insufficient insufficient ->
                    throw new InsufficientStockException(insufficient.insufficientItems());
        }
    }

    private OrderEntity store(UUID orderId, AuthenticatedUser customer, PlaceOrderRequest request,
                              Map<UUID, CatalogProduct> products) {
        Instant now = clock.instant();
        // Title and price from Catalog, never from the client
        List<OrderItemEntity> items = request.items().stream()
                .map(item -> {
                    CatalogProduct product = products.get(item.productId());
                    return OrderItemEntity.snapshot(item.productId(), product.title(), product.basePrice(),
                            item.quantity());
                })
                .toList();
        DeliveryDetailsRequest delivery = request.deliveryDetails();
        OrderEntity order = OrderEntity.place(orderId, customer.id(), customer.email(),
                new DeliveryDetails(delivery.name(), delivery.phone(), delivery.address(), delivery.carrier()),
                items, CURRENCY, now, properties.paymentWindow());
        orders.save(order);
        outbox.append(OrderPlacedEvent.of(order, now));
        return order;
    }

    private void releaseQuietly(UUID orderId) {
        try {
            stock.release(orderId);
        } catch (RuntimeException e) {
            // Nothing else will release it: it needs an operator
            log.error("The reservation of order {} could not be released; its stock may stay reserved until "
                    + "released by hand: {}", orderId, e.getMessage());
        }
    }
}
