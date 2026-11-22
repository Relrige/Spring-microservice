package ua.edu.ukma.springers.voltstore.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

public record CreateNonCustomerUserRequest(
        @NotBlank
        @Email
        @Size(max = 256)
        String email,

        @NotBlank
        @Size(min = 5, max = 64)
        String password,

        @Schema(description = "Any role except CUSTOMER")
        @NotNull
        UserRole role
) {
}
