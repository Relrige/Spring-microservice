package ua.edu.ukma.springers.voltstore.authservice.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.authservice.config.JwtProperties;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService implements TokenService {
    public static final String ROLE_CLAIM = "role";

    private final JWSSigner signer;
    private final Duration expiration;

    public JwtTokenService(JwtProperties properties) {
        try {
            this.signer = new MACSigner(properties.secret().getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException("Invalid JWT signing key", e);
        }
        this.expiration = properties.expiration();
    }

    @Override
    public String issueToken(UserEntity user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim(ROLE_CLAIM, user.getRole().name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(expiration)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
        return jwt.serialize();
    }
}
