package ua.edu.ukma.springers.voltstore.catalog.entities;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "products")
// UPDATE statements contain only the changed columns, so a manager edit never writes back a stale
// stockStatus that an Inventory event changed after this row was loaded (that update does not change the version,
// so it does not make the edit fail either)
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {
    public static final int TITLE_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 5000;
    // NUMERIC(12, 2): up to 9 999 999 999.99
    public static final int PRICE_INTEGER_DIGITS = 10;
    public static final int PRICE_FRACTION_DIGITS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;

    // Referenced by ID only: responses expose categoryId, and no query needs the association
    @Column(nullable = false)
    private UUID categoryId;

    @Column(nullable = false, precision = PRICE_INTEGER_DIGITS + PRICE_FRACTION_DIGITS, scale = PRICE_FRACTION_DIGITS)
    private BigDecimal basePrice;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ProductStatus status;

    // Read-model maintained asynchronously from Inventory events
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private StockStatus stockStatus;

    // Optimistic lock: Hibernate increments it whenever it updates this row and adds "AND version = ?" to the
    // UPDATE, so an overlapping save fails instead of silently winning. Product events carry it so consumers can
    // tell a newer state from an older one. The bulk stock-status update (ProductRepository) leaves it unchanged.
    @Version
    @Column(nullable = false)
    private long version;

    /**
     * New products are hidden from customers until a manager activates them, and are out of stock
     * until Inventory reports otherwise (EP-CAT-01).
     */
    public static Product createNew(String title, String description, UUID categoryId, BigDecimal basePrice) {
        Product product = new Product();
        product.updateDetails(title, description, categoryId, basePrice);
        product.status = ProductStatus.NOT_ACTIVE;
        product.stockStatus = StockStatus.OUT_OF_STOCK;
        return product;
    }

    public void updateDetails(String title, String description, UUID categoryId, BigDecimal basePrice) {
        this.title = title;
        this.description = description;
        this.categoryId = categoryId;
        // Same scale as the column; throws instead of rounding if more fraction digits slip past validation
        this.basePrice = basePrice.setScale(PRICE_FRACTION_DIGITS);
    }

    public void activate() {
        this.status = ProductStatus.ACTIVE;
    }

    // Deactivation is the only removal mechanism: products are never hard-deleted
    public void deactivate() {
        this.status = ProductStatus.NOT_ACTIVE;
    }
}
