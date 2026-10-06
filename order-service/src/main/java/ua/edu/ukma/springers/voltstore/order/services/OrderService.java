package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.order.clients.CatalogClient;
import ua.edu.ukma.springers.voltstore.order.clients.ResilientCatalogClient;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductActivenessStatus;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;
import ua.edu.ukma.springers.voltstore.order.domain.entity.Order;
import ua.edu.ukma.springers.voltstore.order.dto.request.ValidateCheckoutRequest;
import ua.edu.ukma.springers.voltstore.order.exceptions.CheckoutNotValidException;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository orderRepository;
    private final ResilientCatalogClient resilientCatalogClient;

    public void validateCheckout(ValidateCheckoutRequest request) {
        List<UUID> ids = request.getItems().stream().
                map(p -> p.getProductId())
                .toList();

        List<ProductDto> products = resilientCatalogClient.getProductsBatchSafely(ids);

        if (products.isEmpty() && !ids.isEmpty()) {
            throw new IllegalStateException("Catalog validation is temporarily unavailable");
        }

        List<UUID> notActiveProducts = products.stream()
                .filter(p -> p.getActivenessStatus() == ProductActivenessStatus.NOT_ACTIVE)
                .map(p -> p.getId())
                .toList();

        if(!notActiveProducts.isEmpty()) {
            throw new CheckoutNotValidException(notActiveProducts, List.of());
        }
    }

    @Transactional
    public Order createOrder(Order order) {
        order.setStatus(Order.OrderStatus.PENDING_PAYMENT);
        return orderRepository.save(order);
    }

    public Order getOrder(UUID id) {
        return orderRepository.findById(id).orElseThrow();
    }

    public List<Order> getCustomerOrders(UUID customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    @Transactional
    public Order updateOrderStatus(UUID id, Order.OrderStatus status) {
        Order order = getOrder(id);
        order.setStatus(status);
        return orderRepository.save(order);
    }
}