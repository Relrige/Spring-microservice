package ua.edu.ukma.springers.voltstore.authservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

@Data
@EqualsAndHashCode(callSuper = true)
public class CreateNonCustomerUserRequest extends RegisterUserRequest {
    @NotNull
    private UserRole role;
}
