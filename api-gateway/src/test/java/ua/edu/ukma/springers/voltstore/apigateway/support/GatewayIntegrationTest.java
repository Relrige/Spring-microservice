package ua.edu.ukma.springers.voltstore.apigateway.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole gateway on a random port in front of stub upstreams. The stubs are started once per JVM, so all
 * subclasses share one cached Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GatewayIntegrationTest {
    /** Same value as {@code auth.jwt.secret} in the test {@code application.properties}. */
    public static final String SECRET = "test-secret-test-secret-test-secret-12";

    protected static final StubUpstream AUTH = new StubUpstream("auth");
    protected static final StubUpstream CATALOG = new StubUpstream("catalog");
    protected static final StubUpstream INVENTORY = new StubUpstream("inventory");
    protected static final StubUpstream ORDER = new StubUpstream("order");
    private static final Map<String, StubUpstream> STUBS =
            Map.of("auth", AUTH, "catalog", CATALOG, "inventory", INVENTORY, "order", ORDER);

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstreams.auth", AUTH::url);
        registry.add("gateway.upstreams.catalog", CATALOG::url);
        registry.add("gateway.upstreams.inventory", INVENTORY::url);
        registry.add("gateway.upstreams.order", ORDER::url);
    }

    @LocalServerPort
    protected int port;

    protected final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    protected void resetStubs() {
        STUBS.values().forEach(StubUpstream::reset);
    }

    protected static StubUpstream stub(String name) {
        return STUBS.get(name);
    }

    protected static void assertNoUpstreamCalled() {
        STUBS.forEach((name, stub) -> assertThat(stub.received()).as("requests received by " + name).isEmpty());
    }

    /** Sends a request to the gateway; {@code headers} are name/value pairs. */
    protected HttpResponse<String> send(String method, String path, String body, String token, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null || body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static String token(UserRole role) {
        return token(UUID.randomUUID(), role);
    }

    public static String token(UUID userId, UserRole role) {
        return sign(SECRET, new JWTClaimsSet.Builder()
                .subject(userId.toString()).claim("role", role.name())
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build());
    }

    public static String token(UUID userId, UserRole role, String email) {
        return sign(SECRET, new JWTClaimsSet.Builder()
                .subject(userId.toString()).claim("role", role.name()).claim("email", email)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build());
    }

    public static String expiredToken() {
        return sign(SECRET, new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString()).claim("role", UserRole.ADMIN.name())
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))).build());
    }

    public static String adminToken() {
        return token(UserRole.ADMIN);
    }

    public static String sign(String secret, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
