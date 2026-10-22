package ua.edu.ukma.springers.voltstore.inventory.specifications;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import ua.edu.ukma.springers.voltstore.inventory.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;

import java.util.Locale;

/**
 * Composable filters for stock items. The packaging filters must stay equivalent to
 * {@link StockItemEntity#isPackagingConfigured()}: packaging is configured only when all four values are non-null
 * and greater than zero. The product filters work on the copied product data (D8), so rows without a copy match
 * neither.
 */
public final class StockItemSpecifications {
    private static final EscapeCharacter ESCAPE = EscapeCharacter.DEFAULT;

    private StockItemSpecifications() {
    }

    /**
     * Case-insensitive substring of the copied title. {@code %} and {@code _} typed by the user are escaped, so
     * "50%" matches literally instead of matching everything. A leading-wildcard LIKE cannot use a B-tree index
     * (full scan; an index on {@code lower(title)} would not help either); acceptable at this project's size.
     */
    public static Specification<StockItemEntity> titleContains(String text) {
        String pattern = "%" + ESCAPE.escape(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, ESCAPE.getEscapeCharacter());
    }

    public static Specification<StockItemEntity> hasProductStatus(ProductStatus status) {
        return (root, query, cb) -> cb.equal(root.get("productStatus"), status);
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
