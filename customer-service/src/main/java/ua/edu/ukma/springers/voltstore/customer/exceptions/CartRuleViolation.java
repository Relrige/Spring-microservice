package ua.edu.ukma.springers.voltstore.customer.exceptions;

/**
 * The {@code violation} codes of a 422 response; part of the API contract with the client.
 */
public enum CartRuleViolation {
    TOO_MANY_PRODUCTS,
    QUANTITY_LIMIT_EXCEEDED,
    DUPLICATE_PRODUCT
}
