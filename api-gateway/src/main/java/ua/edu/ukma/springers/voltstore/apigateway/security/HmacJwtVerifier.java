package ua.edu.ukma.springers.voltstore.apigateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class HmacJwtVerifier implements JwtVerifier {
    private static final Logger log = LoggerFactory.getLogger(HmacJwtVerifier.class);
    private static final String ROLE_CLAIM = "role";

    private final JWSVerifier verifier;
    private final Duration leeway;
    private final Clock clock;

    public HmacJwtVerifier(JwtProperties properties, Clock clock) {
        try {
            this.verifier = new MACVerifier(properties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid JWT verification key", e);
        }
        this.leeway = properties.leeway();
        this.clock = clock;
    }

    @Override
    public Optional<VerifiedIdentity> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return doVerify(token);
        } catch (ParseException | JOSEException | RuntimeException e) {
            return reject(e.getMessage());
        }
    }

    private Optional<VerifiedIdentity> doVerify(String token) throws ParseException, JOSEException {
        SignedJWT jwt = SignedJWT.parse(token);
        if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
            return reject("unexpected algorithm");
        }
        if (!jwt.verify(verifier)) {
            return reject("bad signature");
        }

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Date expiration = claims.getExpirationTime();
        if (expiration == null) {
            return reject("missing exp");
        }
        if (!expiration.toInstant().plus(leeway).isAfter(Instant.now(clock))) {
            return reject("expired");
        }

        UUID userId = parseUuid(claims.getSubject());
        UserRole role = parseRole(claims.getStringClaim(ROLE_CLAIM));
        if (userId == null || role == null) {
            return reject("invalid sub or role");
        }
        return Optional.of(new VerifiedIdentity(userId, role));
    }

    private static Optional<VerifiedIdentity> reject(String reason) {
        log.debug("JWT rejected: {}", reason);
        return Optional.empty();
    }

    private static UUID parseUuid(String value) {
        try {
            if (value == null) {
                return null;
            }
            UUID parsed = UUID.fromString(value);
            // UUID.fromString accepts shortened forms such as "1-1-1-1-1"; require the canonical form
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UserRole parseRole(String value) {
        try {
            return value == null ? null : UserRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
