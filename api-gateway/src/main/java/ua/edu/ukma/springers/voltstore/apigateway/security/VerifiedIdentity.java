package ua.edu.ukma.springers.voltstore.apigateway.security;

import java.util.Optional;
import java.util.UUID;

/**
 * The caller as proven by a valid JWT. {@code email} is the token's {@code email} claim; it is empty for tokens
 * issued before Auth added the claim, which stay valid until they expire.
 */
public record VerifiedIdentity(UUID userId, UserRole role, Optional<String> email) {

    public VerifiedIdentity(UUID userId, UserRole role) {
        this(userId, role, Optional.empty());
    }
}
