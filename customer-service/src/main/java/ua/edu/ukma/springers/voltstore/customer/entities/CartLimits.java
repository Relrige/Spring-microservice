package ua.edu.ukma.springers.voltstore.customer.entities;

/**
 * The cart invariants from the spec (INV-CART-01, INV-CART-02). The same limits are repeated in the database
 * CHECK constraint of cart_items.
 */
public final class CartLimits {
    /** INV-CART-01: at most this many distinct products (lines) in one cart. */
    public static final int MAX_PRODUCTS = 10;
    /** INV-CART-02: the quantity of one line is between 1 and this value. */
    public static final int MAX_QUANTITY = 10;

    private CartLimits() {
    }
}
