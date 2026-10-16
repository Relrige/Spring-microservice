package ua.edu.ukma.springers.voltstore.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /customers/me/profile}. The phone is in E.164 format (a plus, then 8 to 15 digits without
 * spaces); the default address is optional.
 */
public record ProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be in E.164 format, e.g. +380501234567")
        String phone,
        @Size(max = 500) String defaultAddress) {
}
