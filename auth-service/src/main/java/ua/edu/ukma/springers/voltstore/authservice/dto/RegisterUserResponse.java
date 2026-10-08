package ua.edu.ukma.springers.voltstore.authservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class RegisterUserResponse {
    private UUID userId;
}
