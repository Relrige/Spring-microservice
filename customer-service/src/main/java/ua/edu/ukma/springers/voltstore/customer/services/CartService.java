package ua.edu.ukma.springers.voltstore.customer.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.customer.entities.Cart;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;
import ua.edu.ukma.springers.voltstore.customer.repositories.CartRepository;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Every cart change runs through one of two access paths, each in one transaction that holds a pessimistic lock on
 * the cart row until it commits, so concurrent writes to the same cart run one after another:
 * <ul>
 *   <li>{@link #changeCreatingIfMissing}: for operations that may add items; creates the cart row first.</li>
 *   <li>{@link #changeIfExists}: for operations that only modify or remove; never creates a row, and a missing cart
 *       behaves as an empty one.</li>
 * </ul>
 * The change itself is a call on the {@link Cart} aggregate, which checks the invariants. Reads take no lock.
 */
@Service
@RequiredArgsConstructor
public class CartService {
    private final CartRepository cartRepository;

    /** EP-CUST-03: no lock and no cart creation; a missing cart has no lines. */
    @Transactional(readOnly = true)
    public List<CartLine> getCart(UUID customerId) {
        return cartRepository.findById(customerId).map(Cart::lines).orElseGet(List::of);
    }

    /** Create the cart if missing, lock it, apply the change; returns the resulting lines. */
    @Transactional
    public List<CartLine> changeCreatingIfMissing(UUID customerId, Consumer<Cart> change) {
        cartRepository.createIfMissing(customerId);
        Cart cart = cartRepository.findByIdForUpdate(customerId).orElseThrow();
        change.accept(cart);
        return cart.lines();
    }

    /**
     * Lock the cart if it exists and apply the change; without a cart the change runs on an empty cart that is not
     * stored (so removing does nothing and changing a quantity finds no line). Returns the resulting lines.
     */
    @Transactional
    public List<CartLine> changeIfExists(UUID customerId, Consumer<Cart> change) {
        Cart cart = cartRepository.findByIdForUpdate(customerId).orElseGet(() -> Cart.empty(customerId));
        change.accept(cart);
        return cart.lines();
    }
}
