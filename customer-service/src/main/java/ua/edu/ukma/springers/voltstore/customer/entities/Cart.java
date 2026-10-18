package ua.edu.ukma.springers.voltstore.customer.entities;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartItemNotFoundException;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static ua.edu.ukma.springers.voltstore.customer.entities.CartLimits.MAX_PRODUCTS;
import static ua.edu.ukma.springers.voltstore.customer.entities.CartLimits.MAX_QUANTITY;

/**
 * The cart aggregate root. Every change goes through one of its methods, which check the invariants
 * (INV-CART-01: at most {@value CartLimits#MAX_PRODUCTS} products; INV-CART-02: quantity 1 to
 * {@value CartLimits#MAX_QUANTITY}) on the <b>result</b> and throw {@link CartRuleViolationException} before changing
 * anything, so a rejected operation leaves the cart as it was.
 * <p>
 * There is no {@code @Version}: writers are serialised by a pessimistic lock on the cart row (see
 * {@code CartService}), so optimistic locking would never detect anything. The row itself is created by a native
 * insert ({@code CartRepository.createIfMissing}), never by {@code persist}.
 * <p>
 * Batch changes are applied as a difference (update lines that stay, remove lines that go, add new ones) instead of
 * "delete all, insert all": Hibernate flushes inserts before deletes, so re-inserting a product that is still in the
 * cart would hit the primary key in the same flush.
 */
@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart {
    @Id
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @Getter(AccessLevel.NONE)
    private List<CartItem> items = new ArrayList<>();

    /**
     * An empty cart that is not stored. Used where a customer has no cart row yet and the operation must not create
     * one: reading it gives no lines, removing or clearing does nothing, and changing a quantity finds no line.
     */
    public static Cart empty(UUID customerId) {
        Cart cart = new Cart();
        cart.customerId = customerId;
        cart.createdAt = Instant.now();
        cart.updatedAt = cart.createdAt;
        return cart;
    }

    /** The lines, ordered by product ID so responses do not depend on insertion order. */
    public List<CartLine> lines() {
        return items.stream()
                .map(item -> new CartLine(item.getProductId(), item.getQuantity()))
                .sorted(Comparator.comparing(CartLine::productId))
                .toList();
    }

    /** EP-CUST-04: adds a new line, or increments the existing line of the product. */
    public void add(UUID productId, int quantity) {
        requirePositive(quantity);
        Optional<CartItem> existing = find(productId);
        if (existing.isPresent()) {
            long result = (long) existing.get().getQuantity() + quantity;
            if (result > MAX_QUANTITY) {
                throw CartRuleViolationException.quantityLimitExceeded(List.of(productId));
            }
            existing.get().changeQuantity((int) result);
        } else {
            if (items.size() >= MAX_PRODUCTS) {
                throw CartRuleViolationException.tooManyProducts(items.size() + 1);
            }
            if (quantity > MAX_QUANTITY) {
                throw CartRuleViolationException.quantityLimitExceeded(List.of(productId));
            }
            items.add(new CartItem(this, productId, quantity));
        }
        touch();
    }

    /** EP-CUST-05: sets the quantity of a line that must already exist. */
    public void setQuantity(UUID productId, int quantity) {
        requirePositive(quantity);
        CartItem item = find(productId).orElseThrow(() -> new CartItemNotFoundException(productId));
        if (quantity > MAX_QUANTITY) {
            throw CartRuleViolationException.quantityLimitExceeded(List.of(productId));
        }
        item.changeQuantity(quantity);
        touch();
    }

    /** EP-CUST-06: idempotent; removing a product that is not in the cart does nothing. */
    public void remove(UUID productId) {
        items.removeIf(item -> item.getProductId().equals(productId));
        touch();
    }

    /** EP-CUST-07: the cart becomes exactly the given lines. */
    public void replaceAll(List<CartLine> lines) {
        CartLine.requireNoDuplicates(lines);
        lines.forEach(line -> requirePositive(line.quantity()));
        if (lines.size() > MAX_PRODUCTS) {
            throw CartRuleViolationException.tooManyProducts(lines.size());
        }
        Map<UUID, Long> target = new TreeMap<>();
        lines.forEach(line -> target.put(line.productId(), (long) line.quantity()));
        requireQuantitiesWithinLimit(target);
        apply(target);
    }

    /** EP-CUST-08: adds the given lines, summing the quantities of products that are already in the cart. */
    public void mergeFrom(List<CartLine> lines) {
        CartLine.requireNoDuplicates(lines);
        lines.forEach(line -> requirePositive(line.quantity()));
        Map<UUID, Long> target = new TreeMap<>();
        items.forEach(item -> target.put(item.getProductId(), (long) item.getQuantity()));
        lines.forEach(line -> target.merge(line.productId(), (long) line.quantity(), Long::sum));
        if (target.size() > MAX_PRODUCTS) {
            throw CartRuleViolationException.tooManyProducts(target.size());
        }
        requireQuantitiesWithinLimit(target);
        apply(target);
    }

    /**
     * The OrderPaid consumer (EP-CUST-EVT-01): removes the lines of the ordered products and keeps everything else, so
     * products added between submitting and paying stay. Idempotent: a product that is not in the cart is skipped.
     */
    public void removeAll(Collection<UUID> productIds) {
        Set<UUID> ordered = new HashSet<>(productIds);
        items.removeIf(item -> ordered.contains(item.getProductId()));
        touch();
    }

    /** EP-CUST-09: removes every line; the cart itself stays. */
    public void clear() {
        items.clear();
        touch();
    }

    private Optional<CartItem> find(UUID productId) {
        return items.stream().filter(item -> item.getProductId().equals(productId)).findFirst();
    }

    private static void requirePositive(int quantity) {
        // Request validation rejects this with 400 first; reaching it here is a programming error
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1, got " + quantity);
        }
    }

    private static void requireQuantitiesWithinLimit(Map<UUID, Long> target) {
        List<UUID> overLimit = target.entrySet().stream()
                .filter(entry -> entry.getValue() > MAX_QUANTITY)
                .map(Map.Entry::getKey)
                .toList();
        if (!overLimit.isEmpty()) {
            throw CartRuleViolationException.quantityLimitExceeded(overLimit);
        }
    }

    /** Makes the lines equal to {@code target} (already checked against the invariants). */
    private void apply(Map<UUID, Long> target) {
        items.removeIf(item -> !target.containsKey(item.getProductId()));
        target.forEach((productId, quantity) -> find(productId).ifPresentOrElse(
                item -> item.changeQuantity(quantity.intValue()),
                () -> items.add(new CartItem(this, productId, quantity.intValue()))));
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
