package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;

import java.util.UUID;

@Data
@AllArgsConstructor
public class CategoryResponse {
    private UUID categoryId;
    private String name;

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }
}
