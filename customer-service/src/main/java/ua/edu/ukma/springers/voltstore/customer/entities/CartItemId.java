package ua.edu.ukma.springers.voltstore.customer.entities;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key of {@link CartItem}: {@code (cart_id, product_id)}. The field names match the {@code @Id} fields of
 * the entity; {@code cart} holds the cart's key (the customer ID), not the cart itself.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CartItemId implements Serializable {
    private UUID cart;
    private UUID productId;
}
