package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.order.client.CatalogClient;
import ua.edu.ukma.springers.voltstore.order.client.ProductDto;
import ua.edu.ukma.springers.voltstore.order.domain.entity.Order;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final CatalogClient catalogClient;

    private final OrderRepository orderRepository;

    public ProductDto getProduct(UUID id) {
        log.info("Fetching product details from catalog-service for id={}", id);
        return catalogClient.getById(id);
    }

    public ProductDto getProductSlow(UUID id) {
        log.info("Fetching slow product id={}", id);
        return catalogClient.getByIdSlow(id);
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