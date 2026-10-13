package ua.edu.ukma.springers.voltstore.catalog.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

// EP-CAT-03. A value outside ACTIVE / NOT_ACTIVE already fails while the JSON is read.
@Data
public class ProductStatusRequest {
    @NotNull
    private ProductStatus status;
}
