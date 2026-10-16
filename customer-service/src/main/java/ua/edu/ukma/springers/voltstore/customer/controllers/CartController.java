package ua.edu.ukma.springers.voltstore.customer.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.customer.dto.AddCartItemRequest;
import ua.edu.ukma.springers.voltstore.customer.dto.CartItemResponse;
import ua.edu.ukma.springers.voltstore.customer.dto.CartItemsRequest;
import ua.edu.ukma.springers.voltstore.customer.dto.SetCartItemQuantityRequest;
import ua.edu.ukma.springers.voltstore.customer.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.customer.services.CartService;

import java.util.List;
import java.util.UUID;

/**
 * The customer's own cart. Every response is the whole cart after the operation, ordered by product ID. Who may call
 * which route is declared in SecurityConfig; the cart rules live in the Cart aggregate.
 */
@RestController
@RequestMapping("/customers/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;

    /** EP-CUST-03 */
    @GetMapping
    public List<CartItemResponse> getCart(@AuthenticationPrincipal AuthenticatedUser customer) {
        return CartItemResponse.from(cartService.getCart(customer.id()));
    }

    /** EP-CUST-07: replace the whole cart (idempotent) */
    @PutMapping
    public List<CartItemResponse> replaceCart(@AuthenticationPrincipal AuthenticatedUser customer,
                                              @Valid @RequestBody CartItemsRequest request) {
        return CartItemResponse.from(cartService.replaceCart(customer.id(), request.toLines()));
    }

    /** EP-CUST-08: merge items into the cart, summing quantities (not idempotent) */
    @PostMapping("/merge")
    public List<CartItemResponse> mergeIntoCart(@AuthenticationPrincipal AuthenticatedUser customer,
                                                @Valid @RequestBody CartItemsRequest request) {
        return CartItemResponse.from(cartService.mergeIntoCart(customer.id(), request.toLines()));
    }

    /** EP-CUST-09: delete every item, keep the cart (idempotent) */
    @DeleteMapping
    public List<CartItemResponse> clearCart(@AuthenticationPrincipal AuthenticatedUser customer) {
        return CartItemResponse.from(cartService.clearCart(customer.id()));
    }

    /** EP-CUST-04 */
    @PostMapping("/items")
    public List<CartItemResponse> addItem(@AuthenticationPrincipal AuthenticatedUser customer,
                                          @Valid @RequestBody AddCartItemRequest request) {
        return CartItemResponse.from(cartService.addItem(customer.id(), request.productId(), request.quantity()));
    }

    /** EP-CUST-05 */
    @PatchMapping("/items/{productId}")
    public List<CartItemResponse> setItemQuantity(@AuthenticationPrincipal AuthenticatedUser customer,
                                                  @PathVariable UUID productId,
                                                  @Valid @RequestBody SetCartItemQuantityRequest request) {
        return CartItemResponse.from(cartService.setItemQuantity(customer.id(), productId, request.quantity()));
    }

    /** EP-CUST-06 */
    @DeleteMapping("/items/{productId}")
    public List<CartItemResponse> removeItem(@AuthenticationPrincipal AuthenticatedUser customer,
                                             @PathVariable UUID productId) {
        return CartItemResponse.from(cartService.removeItem(customer.id(), productId));
    }
}
