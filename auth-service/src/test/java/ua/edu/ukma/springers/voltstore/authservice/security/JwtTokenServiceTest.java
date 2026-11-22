package ua.edu.ukma.springers.voltstore.authservice.security;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.authservice.config.JwtProperties;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    @Test
    void issueToken_containsSubjectRoleIssuedAtAndExpiry() throws Exception {
        UserEntity user = user(UserRole.CATALOG_MANAGER);
        JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)));

        SignedJWT jwt = SignedJWT.parse(service.issueToken(user));

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getStringClaim("role")).isEqualTo("CATALOG_MANAGER");
        assertThat(claims.getStringClaim("email")).isEqualTo("u@example.com");
        assertThat(claims.getIssueTime()).isNotNull();
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    void issueToken_isSignedWithConfiguredSecretOnly() throws Exception {
        JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)));

        SignedJWT jwt = SignedJWT.parse(service.issueToken(user(UserRole.CUSTOMER)));

        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(jwt.verify(verifier(SECRET))).isTrue();
        assertThat(jwt.verify(verifier("another-secret-another-secret-12345"))).isFalse();
    }

    @Test
    void issueToken_tamperedPayloadFailsVerification() throws Exception {
        JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)));
        String[] parts = service.issueToken(user(UserRole.CUSTOMER)).split("\\.");
        String forgedClaims = new JWTClaimsSet.Builder().subject(UUID.randomUUID().toString()).claim("role", "ADMIN")
                .expirationTime(jwtTime(Instant.now().plusSeconds(60))).build()
                .toPayload().toBase64URL().toString();

        SignedJWT forged = SignedJWT.parse(parts[0] + "." + forgedClaims + "." + parts[2]);

        assertThat(forged.verify(verifier(SECRET))).isFalse();
    }

    @Test
    void issueToken_expiredTokenIsDetectableByExpClaim() throws Exception {
        JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, Duration.ofSeconds(-1)));

        SignedJWT jwt = SignedJWT.parse(service.issueToken(user(UserRole.CUSTOMER)));

        assertThat(jwt.verify(verifier(SECRET))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isBefore(Instant.now());
    }

    @Test
    void constructor_rejectsTooShortSecret() {
        JwtProperties properties = new JwtProperties("short", Duration.ofHours(1));
        assertThatThrownBy(() -> new JwtTokenService(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JWSVerifier verifier(String secret) throws Exception {
        return new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static UserEntity user(UserRole role) {
        return UserEntity.builder().id(UUID.randomUUID()).email("u@example.com").passwordHash("hash").role(role).build();
    }
    // Nimbus JOSE takes JWT time claims only as java.util.Date; everything else uses java.time
    @SuppressWarnings("java:S2143")
    private static java.util.Date jwtTime(Instant instant) {
        return java.util.Date.from(instant);
    }

}
