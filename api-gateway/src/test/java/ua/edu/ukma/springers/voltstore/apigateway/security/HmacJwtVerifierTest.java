package ua.edu.ukma.springers.voltstore.apigateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HmacJwtVerifierTest {
    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";
    private static final String OTHER_SECRET = "another-secret-another-secret-12345";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration LEEWAY = Duration.ofSeconds(30);

    private final HmacJwtVerifier verifier = new HmacJwtVerifier(
            new JwtProperties(SECRET, LEEWAY), Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void validToken_returnsIdentity(UserRole role) throws Exception {
        String token = token(SECRET, userId.toString(), role.name(), NOW.plusSeconds(3600));

        assertThat(verifier.verify(token)).contains(new VerifiedIdentity(userId, role));
    }

    @Test
    void expiredWithinLeeway_isAccepted() throws Exception {
        String token = token(SECRET, userId.toString(), "CUSTOMER", NOW.minusSeconds(10));

        assertThat(verifier.verify(token)).isPresent();
    }

    @Test
    void expiredBeyondLeeway_isRejected() throws Exception {
        String token = token(SECRET, userId.toString(), "CUSTOMER", NOW.minus(LEEWAY).minusSeconds(1));

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void missingExpiration_isRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString()).claim("role", "CUSTOMER").build();

        assertThat(verifier.verify(sign(SECRET, claims))).isEmpty();
    }

    @Test
    void signedWithDifferentSecret_isRejected() throws Exception {
        String token = token(OTHER_SECRET, userId.toString(), "ADMIN", NOW.plusSeconds(3600));

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void tamperedPayload_isRejected() throws Exception {
        String[] parts = token(SECRET, userId.toString(), "CUSTOMER", NOW.plusSeconds(3600)).split("[.]");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new JWTClaimsSet.Builder().subject(userId.toString()).claim("role", "ADMIN")
                        .expirationTime(Date.from(NOW.plusSeconds(3600))).build()
                        .toString().getBytes(StandardCharsets.UTF_8));

        assertThat(verifier.verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void algorithmNone_isRejected() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString()).claim("role", "ADMIN")
                .expirationTime(Date.from(NOW.plusSeconds(3600))).build();
        String token = new PlainJWT(new PlainHeader(), claims).serialize();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void otherHmacAlgorithm_isRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString()).claim("role", "CUSTOMER")
                .expirationTime(Date.from(NOW.plusSeconds(3600))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        jwt.sign(new MACSigner((SECRET + SECRET).getBytes(StandardCharsets.UTF_8)));

        assertThat(verifier.verify(jwt.serialize())).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "garbage", "a.b.c", "a.b", "..."})
    void malformedToken_isRejected(String token) {
        assertThat(verifier.verify(token)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", ""})
    void invalidSubject_isRejected(String subject) throws Exception {
        assertThat(verifier.verify(token(SECRET, subject, "CUSTOMER", NOW.plusSeconds(3600)))).isEmpty();
    }

    @Test
    void missingSubject_isRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("role", "CUSTOMER").expirationTime(Date.from(NOW.plusSeconds(3600))).build();

        assertThat(verifier.verify(sign(SECRET, claims))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERUSER", "customer", ""})
    void unknownRole_isRejected(String role) throws Exception {
        assertThat(verifier.verify(token(SECRET, userId.toString(), role, NOW.plusSeconds(3600)))).isEmpty();
    }

    @Test
    void missingOrNonStringRole_isRejected() throws Exception {
        Date exp = Date.from(NOW.plusSeconds(3600));
        JWTClaimsSet noRole = new JWTClaimsSet.Builder().subject(userId.toString()).expirationTime(exp).build();
        JWTClaimsSet numericRole = new JWTClaimsSet.Builder().subject(userId.toString()).claim("role", 5)
                .expirationTime(exp).build();

        assertThat(verifier.verify(sign(SECRET, noRole))).isEmpty();
        assertThat(verifier.verify(sign(SECRET, numericRole))).isEmpty();
    }

    private static String token(String secret, String subject, String role, Instant expiration) throws Exception {
        return sign(secret, new JWTClaimsSet.Builder()
                .subject(subject).claim("role", role)
                .issueTime(Date.from(NOW)).expirationTime(Date.from(expiration)).build());
    }

    private static String sign(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
