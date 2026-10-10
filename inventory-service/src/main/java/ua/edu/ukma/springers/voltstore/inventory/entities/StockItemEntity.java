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
}
