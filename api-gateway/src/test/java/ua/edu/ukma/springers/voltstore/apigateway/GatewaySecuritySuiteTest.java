package ua.edu.ukma.springers.voltstore.apigateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import ua.edu.ukma.springers.voltstore.apigateway.routing.AuthMode;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccessPolicy;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;
import ua.edu.ukma.springers.voltstore.apigateway.support.RouteSamples;
import ua.edu.ukma.springers.voltstore.apigateway.support.RouteSamples.Sample;
import ua.edu.ukma.springers.voltstore.apigateway.support.StubUpstream.Received;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting proof of the trust boundary (spec: Trust Boundary, Security Model preconditions). Driven by the route
 * configuration where possible, so it grows with the route table.
 */
class GatewaySecuritySuiteTest extends GatewayIntegrationTest {
    private static final String OTHER_SECRET = "another-secret-another-secret-another-1";

    /** The rows of the spec's route table that are implemented (tasks 03 and 10), as "METHOD path" with {id}. */
    private static final Set<String> SPEC_ROUTES = Set.of(
            "POST /user/register", "POST /auth/login", "POST /user/non-customer",
            "GET /catalog/products", "GET /catalog/products/{id}", "POST /catalog/products",
            "PUT /catalog/products/{id}", "PATCH /catalog/products/{id}/status", "GET /catalog/categories",
            "POST /catalog/categories", "PUT /catalog/categories/{id}", "DELETE /catalog/categories/{id}",
            "GET /inventory/stock", "POST /inventory/stock/{id}/adjust", "PUT /inventory/stock/{id}/packaging",
            "GET /customers/me/profile", "PUT /customers/me/profile", "GET /customers/cart", "PUT /customers/cart",
            "DELETE /customers/cart", "POST /customers/cart/merge", "POST /customers/cart/items",
            "PATCH /customers/cart/items/{id}", "DELETE /customers/cart/items/{id}",
            "POST /delivery/orders/{id}/ttn/retry");

    @Autowired
    GatewayMvcProperties gatewayProperties;

    @Autowired
    RouteAccessPolicy policy;

    private List<Sample> samples() {
        return RouteSamples.from(gatewayProperties, policy);
    }

    @Test
    void configuredRoutes_matchTheSpecRouteTableExactly() {
        Set<String> configured = samples().stream()
                .map(s -> s.method() + " " + s.path().replace(RouteSamples.SAMPLE_ID, "{id}"))
                .collect(Collectors.toSet());

        assertThat(configured).containsExactlyInAnyOrderElementsOf(SPEC_ROUTES);
    }

    /** Every route is reachable by an allowed caller, and the upstream never sees Authorization or spoofed headers. */
    @TestFactory
    List<DynamicTest> everyRoute_reachableByAllowedCaller_withoutAuthorizationOrSpoofedHeaders() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Sample sample : samples()) {
            UserRole role = sample.access().roles().stream().findFirst().orElse(UserRole.CUSTOMER);
            UUID user = UUID.randomUUID();
            tests.add(DynamicTest.dynamicTest(sample.method() + " " + sample.path(), () -> {
                resetStubs();
                var response = send(sample.method(), sample.path(), "{}", token(user, role),
                        "X-User-Id", UUID.randomUUID().toString(), "X-User-Role", "ADMIN",
                        "X-Internal-Token", "forged");

                assertThat(response.statusCode()).isEqualTo(200);
                Received received = stub(sample.upstream()).single();
                assertThat(received.header("Authorization")).isEmpty();
                assertThat(received.header("X-Internal-Token")).isEmpty();
                if (sample.access().mode() == AuthMode.PUBLIC) {
                    assertThat(received.header("X-User-Id")).isEmpty();
                    assertThat(received.header("X-User-Role")).isEmpty();
                } else {
                    assertThat(received.header("X-User-Id")).containsExactly(user.toString());
                    assertThat(received.header("X-User-Role")).containsExactly(role.name());
                }
            }));
        }
        return tests;
    }

    /** Token matrix on a Required and an Optional route: every bad token is a 401 and never reaches the upstream. */
    @TestFactory
    List<DynamicTest> invalidTokens_areRejectedOnRequiredAndOptionalRoutes() {
        Map<String, String> requests = Map.of(
                "REQUIRED GET /inventory/stock", "GET /inventory/stock",
                "OPTIONAL GET /catalog/products", "GET /catalog/products");
        List<DynamicTest> tests = new ArrayList<>();
        requests.forEach((label, request) -> {
            String method = request.split(" ")[0];
            String path = request.split(" ")[1];
            invalidTokens().forEach((kind, token) -> tests.add(DynamicTest.dynamicTest(label + " / " + kind, () -> {
                resetStubs();
                var response = send(method, path, null, null, "Authorization", "Bearer " + token);
                assertThat(response.statusCode()).isEqualTo(401);
                assertNoUpstreamCalled();
            })));
            tests.add(DynamicTest.dynamicTest(label + " / missing", () -> {
                resetStubs();
                int expected = label.startsWith("REQUIRED") ? 401 : 200;
                assertThat(send(method, path, null, null).statusCode()).isEqualTo(expected);
            }));
        });
        return tests;
    }

    @Test
    void methodPathQueryAndBody_reachUpstreamUnchanged() throws Exception {
        String query = "?token=guest-token-123&categoryId=" + RouteSamples.SAMPLE_ID + "&q=usb%20c&page=0&page=1";
        String body = "{\"title\":\"Ноутбук\",\"basePrice\":999.99,\"tags\":[\"a\",\"b\"]}";

        var response = send("PUT", "/catalog/products/" + RouteSamples.SAMPLE_ID + query, body,
                token(UserRole.CATALOG_MANAGER), "Accept-Language", "uk");

        assertThat(response.statusCode()).isEqualTo(200);
        Received received = CATALOG.single();
        assertThat(received.method()).isEqualTo("PUT");
        assertThat(received.uri()).isEqualTo("/catalog/products/" + RouteSamples.SAMPLE_ID + query);
        assertThat(received.body()).isEqualTo(body);
        // The servlet stack re-renders the media type with its charset (application/json;charset=UTF-8)
        assertThat(received.header("Content-Type")).singleElement().asString().startsWith("application/json");
        assertThat(received.header("Accept-Language")).containsExactly("uk");
    }

    private static Map<String, String> invalidTokens() {
        Date future = Date.from(Instant.now().plusSeconds(3600));
        String user = UUID.randomUUID().toString();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("expired", expiredToken());
        tokens.put("bad signature", sign(OTHER_SECRET, claims(user, "ADMIN", future)));
        tokens.put("algorithm none", new PlainJWT(new PlainHeader(), claims(user, "ADMIN", future)).serialize());
        tokens.put("algorithm HS512", hs512(claims(user, "ADMIN", future)));
        tokens.put("malformed", "abc.def");
        tokens.put("unknown role", sign(SECRET, claims(user, "SUPERUSER", future)));
        tokens.put("non-UUID subject", sign(SECRET, claims("admin", "ADMIN", future)));
        tokens.put("missing exp", sign(SECRET, new JWTClaimsSet.Builder().subject(user).claim("role", "ADMIN").build()));
        return tokens;
    }

    private static JWTClaimsSet claims(String subject, String role, Date expiration) {
        return new JWTClaimsSet.Builder().subject(subject).claim("role", role).expirationTime(expiration).build();
    }

    private static String hs512(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
            jwt.sign(new MACSigner((SECRET + SECRET).getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
