package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.dto.FulfillmentQueueResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ShippedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderShippedEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.TtnNotGeneratedException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EP-ORD-07 and EP-ORD-08 for inventory workers. Delivery's shipment status is never stored on the order; both
 * endpoints ask Delivery every time, and Delivery's answer is the source of truth for the TTN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {
    private static final int MAX_SHIP_ATTEMPTS = 3;

    private final OrderRepository orders;
    private final Shipments shipments;
    private final Outbox outbox;
    private final TransactionOperations transactions;
    private final Clock clock;

    /** Delivery unavailable → the orders without delivery data, flagged as such. */
    public FulfillmentQueueResponse queue() {
        List<OrderEntity> waiting = orders.findByStatusInOrderByPaidAtAsc(OrderStatus.AWAITING_DISPATCH);
        if (waiting.isEmpty()) {
            return new FulfillmentQueueResponse(true, List.of());
        }
        Map<UUID, Shipments.ShipmentInfo> byOrder;
        try {
            // The adapter asks in chunks of 100
            byOrder = shipments.find(waiting.stream().map(OrderEntity::getId).toList()).stream()
                    .collect(Collectors.toMap(Shipments.ShipmentInfo::orderId, Function.identity(),
                            (first, second) -> first));
        } catch (DependencyUnavailableException e) {
            log.warn("Showing the fulfillment queue without delivery data: {}", e.getMessage());
            return new FulfillmentQueueResponse(false,
                    waiting.stream().map(order -> FulfillmentQueueResponse.Entry.of(order, null)).toList());
        }
        return new FulfillmentQueueResponse(true, waiting.stream()
                .map(order -> FulfillmentQueueResponse.Entry.of(order, byOrder.get(order.getId())))
                .toList());
    }

    /**
     * @throws OrderNotFoundException         no such order (404)
     * @throws InvalidOrderStateException     not awaiting dispatch (409)
     * @throws TtnNotGeneratedException       Delivery has no TTN for it (yet) (409)
     * @throws DependencyUnavailableException Delivery could not answer (503)
     */
    public ShippedOrderResponse ship(UUID orderId) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        requireAwaitingDispatch(order);
        String ttn = generatedTtn(orderId);

        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(tx -> markShipped(orderId, ttn));
            } catch (OptimisticLockingFailureException e) {
                // Usually TTNGenerated moving PAID → PENDING_SHIPPING at the same moment: read again and retry
                if (attempt == MAX_SHIP_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private String generatedTtn(UUID orderId) {
        List<Shipments.ShipmentInfo> found = shipments.find(List.of(orderId));
        Shipments.ShipmentInfo shipment = found.isEmpty() ? null : found.getFirst();
        if (shipment == null || shipment.status() != Shipments.DeliveryStatus.TTN_GENERATED || shipment.ttn() == null) {
            throw new TtnNotGeneratedException(orderId);
        }
        return shipment.ttn();
    }

    private ShippedOrderResponse markShipped(UUID orderId, String ttn) {
        OrderEntity order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        requireAwaitingDispatch(order);
        // Also from PAID: a TTN Delivery already has counts even if its TTNGenerated event is late
        order.markShipped();
        orders.saveAndFlush(order);
        outbox.append(OrderShippedEvent.of(order, ttn, clock.instant()));
        return new ShippedOrderResponse(orderId, order.getStatus(), ttn);
    }

    private static void requireAwaitingDispatch(OrderEntity order) {
        if (!OrderStatus.AWAITING_DISPATCH.contains(order.getStatus())) {
            throw new InvalidOrderStateException(order.getId(), order.getStatus(), "be shipped");
        }
    }
}
