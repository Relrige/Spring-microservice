package ua.edu.ukma.springers.voltstore.apigateway.security;

import java.util.UUID;

public record VerifiedIdentity(UUID userId, UserRole role) {
}
