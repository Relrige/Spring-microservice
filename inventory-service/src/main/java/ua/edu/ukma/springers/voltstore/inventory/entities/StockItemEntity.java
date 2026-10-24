package ua.edu.ukma.springers.voltstore.inventory.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "stock_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockItemEntity {

    @Id
    private UUID productId;

    @Column(nullable = false)
    @Builder.Default
    private Integer availableQuantity = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    private Integer grossWeightGrams;

    private Integer lengthCm;

    private Integer widthCm;

    private Integer heightCm;

    // Copy of Catalog's product data (D8). Read-only here: only StockItemRepository.upsertProductData writes it, so
    // saving a stock change can never write an old copy back. null = not copied yet.
    @Column(insertable = false, updatable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(insertable = false, updatable = false)
    private ProductStatus productStatus;

    @Column(insertable = false, updatable = false)
    private Long productVersion;

    public boolean isPackagingConfigured() {
        return grossWeightGrams != null && grossWeightGrams > 0
                && lengthCm != null && lengthCm > 0
                && widthCm != null && widthCm > 0
                && heightCm != null && heightCm > 0;
    }

    public Integer getTotalQuantity() {
        return availableQuantity + reservedQuantity;
    }
}
