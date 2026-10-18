package ua.edu.ukma.springers.voltstore.order.security;

import java.util.UUID;

/**
 * The caller as identified by the API Gateway through the trusted {@code X-User-Id}, {@code X-User-Role} and
 * {@code X-User-Email} headers. Available in controllers via {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * @param email the account email from the JWT; {@code null} when the gateway sent none (a token issued before the
 *              {@code email} claim existed). It is data, never an authorization input
 */
public record AuthenticatedUser(UUID id, UserRole role, String email) {

    // The email is personal data: keep it out of logs that print the principal
    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + ", role=" + role + "]";
    }
}
