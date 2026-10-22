package ua.edu.ukma.springers.voltstore.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

@Data
@EqualsAndHashCode(callSuper = true)
public class CreateNonCustomerUserRequest extends RegisterUserRequest {
    @Schema(description = "Any role except CUSTOMER")
    @NotNull
    private UserRole role;
}
