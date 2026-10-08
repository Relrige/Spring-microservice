package ua.edu.ukma.springers.voltstore.apigetaway.security;

import java.util.UUID;

public record VerifiedIdentity(UUID userId, UserRole role) {
}
