package ua.edu.ukma.springers.voltstore.customer.entities;

import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolationException;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One line of a cart as a value: what the cart contains and what a batch request asks for. The aggregate's entities
 * ({@link CartItem}) never leave {@link Cart}.
 */
public record CartLine(UUID productId, int quantity) {

    /**
     * Rejects a batch that lists the same product more than once (DUPLICATE_PRODUCT). Depends only on the request, so
     * callers can check it before taking the cart lock.
     */
    public static void requireNoDuplicates(Collection<CartLine> lines) {
        Set<UUID> seen = new HashSet<>();
        List<UUID> duplicates = lines.stream().map(CartLine::productId).filter(id -> !seen.add(id)).toList();
        if (!duplicates.isEmpty()) {
            throw CartRuleViolationException.duplicateProduct(duplicates);
        }
    }
}
