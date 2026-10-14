package ua.edu.ukma.springers.voltstore.catalog.exceptions;

public class CategoryNameAlreadyInUseException extends RuntimeException {
    public CategoryNameAlreadyInUseException() {
        super("Category name already in use");
    }
}
