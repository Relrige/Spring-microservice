package ua.edu.ukma.springers.voltstore.catalog.exceptions;

import java.util.UUID;

public class CategoryNotFoundException extends RuntimeException {
    public CategoryNotFoundException(UUID id) {
        super("Category with ID " + id + " not found");
    }
}
