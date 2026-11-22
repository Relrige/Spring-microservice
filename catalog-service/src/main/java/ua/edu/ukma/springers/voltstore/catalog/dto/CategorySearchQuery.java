package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import ua.edu.ukma.springers.voltstore.catalog.utils.Paging;

// Query parameters of GET /catalog/categories (EP-CAT-10); all optional
public record CategorySearchQuery(
        // Case-insensitive substring of the name
        String search,

        // page × size must fit the int row offset Spring Data passes to JPA
        @Parameter(description = "Zero-based page number, default 0")
        @PositiveOrZero
        @Max(Integer.MAX_VALUE / Paging.MAX_SIZE)
        Integer page,

        @Parameter(description = "Page size, default 20, at most 100")
        @Positive
        @Max(Paging.MAX_SIZE)
        Integer size
) {
}
