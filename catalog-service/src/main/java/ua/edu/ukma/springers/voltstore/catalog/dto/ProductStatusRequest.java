package ua.edu.ukma.springers.voltstore.catalog.dto;

import jakarta.validation.constraints.NotNull;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

// EP-CAT-03. A value outside ACTIVE / NOT_ACTIVE already fails while the JSON is read.
public record ProductStatusRequest(
        @NotNull
        ProductStatus status
) {
}
