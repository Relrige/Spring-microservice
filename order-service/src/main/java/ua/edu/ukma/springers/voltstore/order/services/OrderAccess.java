package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;

import java.util.UUID;

/**
 * The one place that loads an order on behalf of a customer (pay, details, cancel). The order is looked up by its ID
 * <b>and</b> the caller's customer ID in one query, so another customer's order is not found, exactly like an order
 * that does not exist: customers cannot probe which order IDs exist.
 */
@Component
@RequiredArgsConstructor
public class OrderAccess {
    private final OrderRepository orderRepository;

    /**
     * @throws OrderNotFoundException the order does not exist or belongs to another customer (both answered 404)
     */
    public OrderEntity loadOwned(UUID orderId, AuthenticatedUser customer) {
        return orderRepository.findByIdAndCustomerId(orderId, customer.id())
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
