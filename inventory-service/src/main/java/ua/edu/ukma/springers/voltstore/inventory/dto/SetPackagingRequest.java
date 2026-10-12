package ua.edu.ukma.springers.voltstore.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SetPackagingRequest(
        @NotNull(message = "Gross weight is required")
        @Positive(message = "Gross weight must be positive")
        Integer grossWeightGrams,

        @NotNull(message = "Length is required")
        @Positive(message = "Length must be positive")
        Integer lengthCm,

        @NotNull(message = "Width is required")
        @Positive(message = "Width must be positive")
        Integer widthCm,

        @NotNull(message = "Height is required")
        @Positive(message = "Height must be positive")
        Integer heightCm
) {
}
