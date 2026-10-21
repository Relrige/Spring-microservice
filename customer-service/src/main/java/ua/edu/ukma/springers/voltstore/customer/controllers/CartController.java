package ua.edu.ukma.springers.voltstore.customer.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import ua.edu.ukma.springers.voltstore.customer.openapi.CartRuleProblem;
import ua.edu.ukma.springers.voltstore.customer.openapi.Problem;
import ua.edu.ukma.springers.voltstore.customer.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.customer.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.customer.services.CartService;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.customer.openapi.OpenApiConfig.BEARER_AUTH;

/**
 * The customer's own cart. Every response is the whole cart after the operation, ordered by product ID. Who may call
 * which route is declared in SecurityConfig; the cart rules live in the Cart aggregate.
 * <p>
 * Every operation requires CUSTOMER, so the bearer requirement and the 401/403 responses are repeated on each method:
 * springdoc applies {@code @ApiResponse} per method, not per class.
 */
@Tag(name = "Cart")
@RestController
@RequestMapping("/customers/cart")
@RequiredArgsConstructor
public class CartController {
    private static final String CART_RULES = " Cart rules: at most 10 distinct products, quantity 1 to 10 per product.";

    private final CartService cartService;

    /** EP-CUST-03 */
    @Operation(summary = "Get my cart (EP-CUST-03)",
            description = "Role: CUSTOMER. An empty or never-created cart is `[]`.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The cart, ordered by product ID")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping
    public List<CartItemResponse> getCart(@AuthenticationPrincipal AuthenticatedUser customer) {
        return CartItemResponse.from(cartService.getCart(customer.id()));
    }

    /** EP-CUST-07: replace the whole cart (idempotent) */
    @Operation(summary = "Replace my cart (EP-CUST-07)",
            description = "Role: CUSTOMER. Idempotent; `items` may be empty." + CART_RULES,
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The new cart")
    @ApiResponse(responseCode = "400", description = "Malformed request or item",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "A cart rule is broken, or a product is listed twice; the cart is unchanged",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = CartRuleProblem.class)))
    @PutMapping
    public List<CartItemResponse> replaceCart(@AuthenticationPrincipal AuthenticatedUser customer,
                                              @Valid @RequestBody CartItemsRequest request) {
        return CartItemResponse.from(cartService.replaceCart(customer.id(), request.toLines()));
    }

    /** EP-CUST-08: merge items into the cart, summing quantities (not idempotent) */
    @Operation(summary = "Merge items into my cart (EP-CUST-08)",
            description = "Role: CUSTOMER. Quantities of products already in the cart are summed; not idempotent."
                    + CART_RULES,
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The merged cart")
    @ApiResponse(responseCode = "400", description = "Malformed request or item",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "A cart rule is broken, or a product is listed twice; the cart is unchanged",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = CartRuleProblem.class)))
    @PostMapping("/merge")
    public List<CartItemResponse> mergeIntoCart(@AuthenticationPrincipal AuthenticatedUser customer,
                                                @Valid @RequestBody CartItemsRequest request) {
        return CartItemResponse.from(cartService.mergeIntoCart(customer.id(), request.toLines()));
    }

    /** EP-CUST-09: delete every item, keep the cart (idempotent) */
    @Operation(summary = "Clear my cart (EP-CUST-09)", description = "Role: CUSTOMER. Idempotent; answers `[]`.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The empty cart")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @DeleteMapping
    public List<CartItemResponse> clearCart(@AuthenticationPrincipal AuthenticatedUser customer) {
        return CartItemResponse.from(cartService.clearCart(customer.id()));
    }

    /** EP-CUST-04 */
    @Operation(summary = "Add an item to my cart (EP-CUST-04)",
            description = "Role: CUSTOMER. A product already in the cart gets its quantity increased." + CART_RULES,
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The updated cart")
    @ApiResponse(responseCode = "400", description = "Invalid fields",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "A cart rule is broken; the cart is unchanged",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = CartRuleProblem.class)))
    @PostMapping("/items")
    public List<CartItemResponse> addItem(@AuthenticationPrincipal AuthenticatedUser customer,
                                          @Valid @RequestBody AddCartItemRequest request) {
        return CartItemResponse.from(cartService.addItem(customer.id(), request.productId(), request.quantity()));
    }

    /** EP-CUST-05 */
    @Operation(summary = "Set the quantity of a cart item (EP-CUST-05)", description = "Role: CUSTOMER." + CART_RULES,
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The updated cart")
    @ApiResponse(responseCode = "400", description = "Invalid quantity or product ID",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "The product is not in the cart",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "The quantity exceeds 10; the cart is unchanged",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = CartRuleProblem.class)))
    @PatchMapping("/items/{productId}")
    public List<CartItemResponse> setItemQuantity(@AuthenticationPrincipal AuthenticatedUser customer,
                                                  @PathVariable UUID productId,
                                                  @Valid @RequestBody SetCartItemQuantityRequest request) {
        return CartItemResponse.from(cartService.setItemQuantity(customer.id(), productId, request.quantity()));
    }

    /** EP-CUST-06 */
    @Operation(summary = "Remove an item from my cart (EP-CUST-06)",
            description = "Role: CUSTOMER. Idempotent: a product that is not in the cart is not an error.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The updated cart")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @DeleteMapping("/items/{productId}")
    public List<CartItemResponse> removeItem(@AuthenticationPrincipal AuthenticatedUser customer,
                                             @PathVariable UUID productId) {
        return CartItemResponse.from(cartService.removeItem(customer.id(), productId));
    }
}
