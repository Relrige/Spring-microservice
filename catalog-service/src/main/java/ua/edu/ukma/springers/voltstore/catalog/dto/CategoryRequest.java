package ua.edu.ukma.springers.voltstore.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;

// Used by both create (EP-CAT-06) and rename (EP-CAT-07)
@Data
public class CategoryRequest {
    @NotBlank
    @Size(max = Category.NAME_MAX_LENGTH)
    private String name;
}
