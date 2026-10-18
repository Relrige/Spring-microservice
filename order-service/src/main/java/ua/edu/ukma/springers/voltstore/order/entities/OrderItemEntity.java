package ua.edu.ukma.springers.voltstore.order.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One product line of an order, with the title and price Catalog returned when the order was placed. Immutable:
 * later price changes in Catalog never touch it.
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private String productTitle;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceAtOrder;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    private OrderItemEntity(UUID productId, String productTitle, BigDecimal unitPriceAtOrder, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        if (unitPriceAtOrder.signum() < 0) {
            throw new IllegalArgumentException("unitPriceAtOrder must not be negative");
        }
        this.productId = Objects.requireNonNull(productId);
        this.productTitle = Objects.requireNonNull(productTitle);
        this.unitPriceAtOrder = unitPriceAtOrder;
        this.quantity = quantity;
    }

    public static OrderItemEntity snapshot(UUID productId, String productTitle, BigDecimal unitPriceAtOrder,
                                           int quantity) {
        return new OrderItemEntity(productId, productTitle, Objects.requireNonNull(unitPriceAtOrder), quantity);
    }

    public BigDecimal lineTotal() {
        return unitPriceAtOrder.multiply(BigDecimal.valueOf(quantity));
    }
}
