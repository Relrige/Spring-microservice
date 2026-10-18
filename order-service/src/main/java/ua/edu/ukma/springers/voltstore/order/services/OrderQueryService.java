package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.order.dto.OrderDetailsResponse;
import ua.edu.ukma.springers.voltstore.order.dto.OrderSummaryResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.paging.PageResponse;
import ua.edu.ukma.springers.voltstore.order.paging.Paging;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;

import java.util.List;
import java.util.UUID;

/** EP-ORD-04 and EP-ORD-05: a customer's own orders. Delivery is optional: without it the order is still shown. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {
    // Newest first; the ID keeps page boundaries stable when two orders have the same placedAt
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id"));

    private final OrderRepository orders;
    private final OrderAccess orderAccess;
    private final Shipments shipments;

    public PageResponse<OrderSummaryResponse> history(AuthenticatedUser customer, int page, int size) {
        return PageResponse.from(orders.findByCustomerId(customer.id(), Paging.of(page, size, NEWEST_FIRST)),
                OrderSummaryResponse::of);
    }

    /**
     * @throws OrderNotFoundException not the caller's order, or none (404)
     */
    public OrderDetailsResponse details(UUID orderId, AuthenticatedUser customer) {
        OrderEntity order = orderAccess.loadOwned(orderId, customer);
        // Only a paid order (also one cancelled after payment) can have a shipment
        if (order.getPaidAt() == null) {
            return OrderDetailsResponse.of(order, null, null);
        }
        Shipments.ShipmentInfo shipment = findShipment(orderId);
        return shipment == null
                ? OrderDetailsResponse.of(order, null, null)
                : OrderDetailsResponse.of(order, shipment.ttn(), shipment.status());
    }

    private Shipments.ShipmentInfo findShipment(UUID orderId) {
        try {
            List<Shipments.ShipmentInfo> found = shipments.find(List.of(orderId));
            return found.isEmpty() ? null : found.getFirst();
        } catch (DependencyUnavailableException e) {
            // Degrade: the order matters more than its tracking number
            log.warn("Showing order {} without its shipment: {}", orderId, e.getMessage());
            return null;
        }
    }
}
