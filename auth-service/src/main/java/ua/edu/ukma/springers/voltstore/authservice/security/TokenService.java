package ua.edu.ukma.springers.voltstore.authservice.security;

import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;

public interface TokenService {
    /**
     * Issues a signed JWT with claims {@code sub} (user id), {@code role}, {@code email} (the stored, normalized
     * account email), {@code iat} and {@code exp}. The token is signed, not encrypted, so it carries no other
     * personal data.
     */
    String issueToken(UserEntity user);
}
