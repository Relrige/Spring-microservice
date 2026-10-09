package ua.edu.ukma.springers.voltstore.apigateway.security;

import java.util.Optional;

public interface JwtVerifier {

    /**
     * Verifies a bearer token. Never throws for bad input: any problem
     * (malformed, wrong signature or algorithm, expired, bad claims) yields an empty result.
     */
    Optional<VerifiedIdentity> verify(String token);
}
