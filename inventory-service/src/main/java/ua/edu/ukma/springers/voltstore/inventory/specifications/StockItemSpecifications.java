package ua.edu.ukma.springers.voltstore.inventory.specifications;

import org.springframework.data.jpa.domain.Specification;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

/**
 * Composable filters for stock items. They must stay equivalent to {@link StockItemEntity#isPackagingConfigured()}:
 * packaging is configured only when all four values are non-null and greater than zero.
 */
public final class StockItemSpecifications {
    private StockItemSpecifications() {
    }

    public static Specification<StockItemEntity> packagingConfigured(boolean configured) {
        return configured ? allDimensionsPositive() : someDimensionMissing();
    }

    private static Specification<StockItemEntity> allDimensionsPositive() {
        // A NULL dimension makes "> 0" unknown, which a WHERE clause treats as false, as required
        return (root, query, cb) -> cb.and(
                cb.greaterThan(root.<Integer>get("grossWeightGrams"), 0),
                cb.greaterThan(root.<Integer>get("lengthCm"), 0),
                cb.greaterThan(root.<Integer>get("widthCm"), 0),
                cb.greaterThan(root.<Integer>get("heightCm"), 0));
    }

    private static Specification<StockItemEntity> someDimensionMissing() {
        // Spelled out instead of NOT(allDimensionsPositive): NOT(unknown) is unknown, so NULL rows would be lost
        return (root, query, cb) -> cb.or(
                cb.isNull(root.get("grossWeightGrams")), cb.lessThanOrEqualTo(root.<Integer>get("grossWeightGrams"), 0),
                cb.isNull(root.get("lengthCm")), cb.lessThanOrEqualTo(root.<Integer>get("lengthCm"), 0),
                cb.isNull(root.get("widthCm")), cb.lessThanOrEqualTo(root.<Integer>get("widthCm"), 0),
                cb.isNull(root.get("heightCm")), cb.lessThanOrEqualTo(root.<Integer>get("heightCm"), 0));
    }
}
