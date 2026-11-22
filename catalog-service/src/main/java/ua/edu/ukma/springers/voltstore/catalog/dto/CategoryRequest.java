package ua.edu.ukma.springers.voltstore.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;

// Used by both create (EP-CAT-06) and rename (EP-CAT-07)
public record CategoryRequest(
        @NotBlank
        @Size(max = Category.NAME_MAX_LENGTH)
        String name
) {
}
