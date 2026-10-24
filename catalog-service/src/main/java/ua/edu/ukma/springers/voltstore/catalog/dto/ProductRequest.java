package ua.edu.ukma.springers.voltstore.catalog.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Editable product fields, used by both create (EP-CAT-01) and update (EP-CAT-02).
 * There is deliberately no status/stockStatus here: values sent by the client for them are ignored.
 */
@Data
public class ProductRequest {
    @NotBlank
    @Size(max = Product.TITLE_MAX_LENGTH)
    private String title;

    @NotBlank
    @Size(max = Product.DESCRIPTION_MAX_LENGTH)
    private String description;

    @NotNull
    private UUID categoryId;

    // A value such as 10.999 is rejected rather than silently rounded to the column scale
    @NotNull
    @Positive
    @Digits(integer = Product.PRICE_INTEGER_DIGITS, fraction = Product.PRICE_FRACTION_DIGITS)
    private BigDecimal basePrice;

    // Optional; omitted or null means "no image" (an update clears it). Relative paths are served by the frontend.
    @Size(max = Product.IMAGE_URL_MAX_LENGTH)
    @Pattern(regexp = IMAGE_URL_PATTERN, message = "must be an https URL or a path under /images/")
    private String imageUrl;

    // https://<anything without whitespace>, or /images/<letters, digits, . _ - /> without "..". Anchored so the
    // OpenAPI pattern (which JSON Schema matches anywhere in the string) means the same as @Pattern
    static final String IMAGE_URL_PATTERN = "^(https://\\S+|/images/(?!.*\\.\\.)[A-Za-z0-9._/-]+)$";
}
