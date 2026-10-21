package ua.edu.ukma.springers.voltstore.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Data
@AllArgsConstructor
public class RegisterUserResponse {
    @Schema(requiredMode = REQUIRED)
    private UUID userId;
}
