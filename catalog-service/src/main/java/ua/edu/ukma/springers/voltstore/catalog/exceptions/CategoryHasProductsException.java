package ua.edu.ukma.springers.voltstore.catalog.exceptions;

public class CategoryHasProductsException extends RuntimeException {
    public CategoryHasProductsException() {
        super("Category has associated products. Reassign or remove all products before deletion.");
    }
}
