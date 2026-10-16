package ua.edu.ukma.springers.voltstore.customer.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A line of a {@link Cart}. It has no meaning outside its cart, so it is only created, changed and removed by the
 * cart (package-private constructor and mutator) and has no repository of its own.
 */
@Entity
@Table(name = "cart_items")
@IdClass(CartItemId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id")
    @Getter(AccessLevel.NONE)
    private Cart cart;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    CartItem(Cart cart, UUID productId, int quantity) {
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    void changeQuantity(int quantity) {
        this.quantity = quantity;
    }
}
