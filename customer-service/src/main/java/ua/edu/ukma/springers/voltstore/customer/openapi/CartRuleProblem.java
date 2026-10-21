package ua.edu.ukma.springers.voltstore.customer.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Documentation only: the {@code 422} of the cart operations. The detail properties depend on {@code violation},
 * exactly as {@code CartRuleViolationException} sets them: {@code TOO_MANY_PRODUCTS} → {@code resultingProductCount},
 * {@code maxProducts}; {@code QUANTITY_LIMIT_EXCEEDED} → {@code productIds}, {@code maxQuantity};
 * {@code DUPLICATE_PRODUCT} → {@code productIds}.
 */
@Schema(name = "CartRuleProblem", description = "The request breaks a cart rule; the cart is unchanged (422)")
public record CartRuleProblem(
        @Schema(requiredMode = REQUIRED) URI type,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(requiredMode = REQUIRED) int status,
        String detail,
        URI instance,
        Instant timestamp,
        String service,
        @Schema(requiredMode = REQUIRED) CartRuleViolation violation,
        @Schema(description = "TOO_MANY_PRODUCTS: distinct products the cart would contain")
        Integer resultingProductCount,
        @Schema(description = "TOO_MANY_PRODUCTS: the limit")
        Integer maxProducts,
        @Schema(description = "QUANTITY_LIMIT_EXCEEDED, DUPLICATE_PRODUCT: the products concerned, sorted")
        List<UUID> productIds,
        @Schema(description = "QUANTITY_LIMIT_EXCEEDED: the limit per product")
        Integer maxQuantity) {
}
