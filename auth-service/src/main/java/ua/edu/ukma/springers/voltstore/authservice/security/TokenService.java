package ua.edu.ukma.springers.voltstore.authservice.security;

import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;

public interface TokenService {
    /**
     * Issues a signed JWT with claims {@code sub} (user id), {@code role}, {@code iat} and {@code exp}.
     */
    String issueToken(UserEntity user);
}
