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
import ua.edu.ukma.springers.voltstore.order.entities.ReservationAttempt;
import ua.edu.ukma.springers.voltstore.order.events.OrderPlacedEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderTotalTooLargeException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.repositories.ReservationAttemptRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EP-ORD-02: turns a cart into an order. The steps run in this order so that stock is never left reserved without
 * an order (only orders expire, so nothing else would ever release it):
 * <ol>
 *   <li>Catalog: current titles, prices and statuses, and the total. Nothing is reserved yet, so a refusal costs
 *   nothing.</li>
 *   <li>A {@link ReservationAttempt} is stored, then Inventory reserves all items under the new order's ID, all or
 *   nothing.</li>
 *   <li>One local transaction: the order with its snapshots, {@code OrderPlaced} in the outbox, and the attempt
 *   deleted.</li>
 * </ol>
 * No database transaction is open during the two remote calls. If anything fails after the reservation may exist,
 * the reservation is released at once (best effort; release is idempotent and does nothing when nothing is
 * reserved), and the attempt stays: {@code OrphanReservationJob} releases it again once any late answer has arrived,
 * so stock is never left reserved, even if this instance dies or Inventory reserves after the release.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPlacementService {
    static final String CURRENCY = "UAH";
    /** The largest amount Payment charges at once (10 integer digits), also the size of {@code orders.total_amount}. */
    static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");

    private final ProductCatalog catalog;
    private final StockReservations stock;
    private final OrderRepository orders;
    private final ReservationAttemptRepository attempts;
    private final Outbox outbox;
    private final TransactionOperations transactions;
    private final CheckoutProperties properties;
    private final Clock clock;

    /**
     * @throws MissingCustomerEmailException  the gateway sent no email (answered 401)
     * @throws IneligibleItemsException       a product is unknown or not active; nothing was reserved
     * @throws OrderTotalTooLargeException    the total exceeds what Payment can charge at once; nothing was reserved
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
        BigDecimal total = request.items().stream()
                .map(item -> products.get(item.productId()).basePrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new OrderTotalTooLargeException(MAX_TOTAL);
        }

        // Written down before the call: if the order is never stored, the job finds and releases the reservation
        transactions.executeWithoutResult(tx -> attempts.save(new ReservationAttempt(orderId, clock.instant())));
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
            // A call that timed out may have reserved the stock anyway, or may still do so; the attempt stays, so
            // the job releases again once a late answer has arrived
            releaseQuietly(orderId);
            throw e;
        }
        switch (result) {
            // Also an earlier call for this order whose answer was lost (409)
            case StockReservations.Reserved reserved -> {
            }
            case StockReservations.Insufficient insufficient -> {
                // Inventory reserved nothing, so there is nothing to clean up
                attempts.deleteById(orderId);
                throw new InsufficientStockException(insufficient.insufficientItems());
            }
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
        // From now on the reservation belongs to a stored order, which the expiry job or the saga releases
        attempts.deleteById(orderId);
        return order;
    }

    private void releaseQuietly(UUID orderId) {
        try {
            stock.release(orderId);
        } catch (RuntimeException e) {
            log.warn("The reservation of order {} could not be released now ({}); OrphanReservationJob retries",
                    orderId, e.getMessage());
        }
    }
}
