package ua.edu.ukma.springers.voltstore.customer.security;

import java.util.UUID;

/**
 * The caller as identified by the API Gateway through the trusted {@code X-User-Id} and {@code X-User-Role} headers.
 * Available in controllers via {@code @AuthenticationPrincipal AuthenticatedUser user}.
 */
public record AuthenticatedUser(UUID id, UserRole role) {
}
