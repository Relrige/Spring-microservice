package ua.edu.ukma.springers.voltstore.catalog.exceptions;

import java.util.UUID;

/**
 * The request is well-formed, but the category it assigns the product to does not exist (422, not 404:
 * the product itself was found or is being created).
 */
public class CategoryNotFoundForProductException extends RuntimeException {
    public CategoryNotFoundForProductException(UUID categoryId) {
        super("Category not found: " + categoryId);
    }
}
