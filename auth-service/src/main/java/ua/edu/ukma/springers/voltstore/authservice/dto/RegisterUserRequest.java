package ua.edu.ukma.springers.voltstore.authservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterUserRequest(
        @NotBlank
        @Email
        @Size(max = 256)
        String email,

        @NotBlank
        @Size(min = 5, max = 64)
        String password
) {
}
