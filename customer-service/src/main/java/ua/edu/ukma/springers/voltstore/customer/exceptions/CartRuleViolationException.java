package ua.edu.ukma.springers.voltstore.customer.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLimits;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A well-formed cart request that breaks a cart rule (mapped to 422). Thrown by the Cart aggregate before it changes
 * anything, so the cart stays as it was.
 */
@Getter
public class CartRuleViolationException extends RuntimeException {
    private final CartRuleViolation violation;
    /** Extra Problem Details properties, in the order they are documented in the spec. */
    private final Map<String, Object> details;

    private CartRuleViolationException(CartRuleViolation violation, String message, Map<String, Object> details) {
        super(message);
        this.violation = violation;
        this.details = details;
    }

    public static CartRuleViolationException tooManyProducts(int resultingProductCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resultingProductCount", resultingProductCount);
        details.put("maxProducts", CartLimits.MAX_PRODUCTS);
        return new CartRuleViolationException(CartRuleViolation.TOO_MANY_PRODUCTS,
                "The cart would contain " + resultingProductCount + " products; at most "
                        + CartLimits.MAX_PRODUCTS + " are allowed.", details);
    }

    public static CartRuleViolationException quantityLimitExceeded(Collection<UUID> productIds) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productIds", sorted(productIds));
        details.put("maxQuantity", CartLimits.MAX_QUANTITY);
        return new CartRuleViolationException(CartRuleViolation.QUANTITY_LIMIT_EXCEEDED,
                "The quantity of a product would exceed " + CartLimits.MAX_QUANTITY + ".", details);
    }

    public static CartRuleViolationException duplicateProduct(Collection<UUID> productIds) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("productIds", sorted(productIds));
        return new CartRuleViolationException(CartRuleViolation.DUPLICATE_PRODUCT,
                "The request lists the same product more than once.", details);
    }

    private static List<UUID> sorted(Collection<UUID> productIds) {
        return productIds.stream().distinct().sorted().toList();
    }
}
