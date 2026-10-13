package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Building blocks for the optional filters of the product listing (EP-CAT-04). The service adds only the
 * filters that were requested and combines them with {@link Specification#allOf}, so one repository method
 * covers every combination (instead of a JPQL query full of {@code :param IS NULL OR ...} conditions).
 */
public final class ProductSpecifications {
    private static final EscapeCharacter ESCAPE = EscapeCharacter.DEFAULT;

    private ProductSpecifications() {
    }

    public static Specification<Product> hasStatus(ProductStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Product> inCategory(UUID categoryId) {
        return (root, query, cb) -> cb.equal(root.get("categoryId"), categoryId);
    }

    public static Specification<Product> priceAtLeast(BigDecimal minPrice) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("basePrice"), minPrice);
    }

    public static Specification<Product> priceAtMost(BigDecimal maxPrice) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("basePrice"), maxPrice);
    }

    /**
     * Case-insensitive substring match on title or description. {@code %} and {@code _} typed by the user are
     * escaped, so "50%" matches literally instead of matching everything. A leading-wildcard LIKE cannot use a
     * B-tree index (full scan); acceptable at this project's size.
     */
    public static Specification<Product> titleOrDescriptionContains(String text) {
        String pattern = "%" + ESCAPE.escape(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, ESCAPE.getEscapeCharacter()),
                cb.like(cb.lower(root.get("description")), pattern, ESCAPE.getEscapeCharacter()));
    }
}
