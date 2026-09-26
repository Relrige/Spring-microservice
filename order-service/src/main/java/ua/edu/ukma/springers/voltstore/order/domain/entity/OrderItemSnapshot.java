package ua.edu.ukma.springers.voltstore.order.domain.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;
import java.math.BigDecimal;

@Embeddable
@Data
public class OrderItemSnapshot {
    private String sku;
    private String title;
    private BigDecimal priceAtPurchase;
    private Integer quantity;
}
