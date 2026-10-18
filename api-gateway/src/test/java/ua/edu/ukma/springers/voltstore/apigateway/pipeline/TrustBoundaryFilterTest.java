package ua.edu.ukma.springers.voltstore.apigateway.pipeline;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccess;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccessPolicy;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;
import ua.edu.ukma.springers.voltstore.apigateway.support.RouteSamples;
import ua.edu.ukma.springers.voltstore.apigateway.support.RouteSamples.Sample;
import ua.edu.ukma.springers.voltstore.apigateway.support.StubUpstream.Received;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrustBoundaryFilterTest extends GatewayIntegrationTest {
    private static final String SPOOFED_ID = "00000000-0000-0000-0000-000000000001";
    private static final String[] SPOOFED_HEADERS = {
            "X-User-Id", SPOOFED_ID,
            "X-User-Role", "ADMIN",
            "x-user-role", "CATALOG_MANAGER",
            "X-User-Email", "victim@example.com",
            "x-USER-email", "second@example.com",
            "X-INTERNAL-TOKEN", "change-me-local-internal-token-at-least-32-chars",
            "x-internal-token", "second-value"
    };

    @Autowired
    GatewayMvcProperties gatewayProperties;

    @Autowired
    RouteAccessPolicy policy;

    // --- Strip ---

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST, /auth/login,         auth,    PUBLIC",
            "GET,  /catalog/products,   catalog, OPTIONAL",
            "GET,  /inventory/stock,    inventory, REQUIRED"
    })
    void spoofedHeadersWithoutToken_neverReachUpstream(String method, String path, String upstream, String mode)
            throws Exception {
        HttpResponse<String> response = send(method, path, "{}", null, SPOOFED_HEADERS);

        if (mode.equals("REQUIRED")) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertNoUpstreamCalled();
        } else {
            assertThat(response.statusCode()).isEqualTo(200);
            assertAnonymous(stub(upstream).single());
        }
    }

    @ParameterizedTest(name = "{0} {1} ({2})")
    @CsvSource({
            "POST, /auth/login,         auth,      PUBLIC,   CUSTOMER",
            "GET,  /catalog/products,   catalog,   OPTIONAL, CUSTOMER",
            "GET,  /inventory/stock,    inventory, REQUIRED, INVENTORY_WORKER"
    })
    void spoofedHeadersWithValidTokenOfAnotherUser_areReplacedByTokenIdentity(
            String method, String path, String upstream, String mode, UserRole role) throws Exception {
        UUID realUser = UUID.randomUUID();

        HttpResponse<String> response = send(method, path, "{}", token(realUser, role), SPOOFED_HEADERS);

        assertThat(response.statusCode()).isEqualTo(200);
        Received received = stub(upstream).single();
        assertThat(received.header("X-Internal-Token")).isEmpty();
        assertThat(received.header("Authorization")).isEmpty();
        if (mode.equals("PUBLIC")) {
            assertAnonymous(received);
        } else {
            assertThat(received.header("X-User-Id")).containsExactly(realUser.toString());
            assertThat(received.header("X-User-Role")).containsExactly(role.name());
        }
        // The token has no email claim, so the forged value must not survive either
        assertThat(received.header("X-User-Email")).isEmpty();
    }

    // --- X-User-Email ---

    @Test
    void tokenWithEmailClaim_forwardsItAsUserEmail_andReplacesForgedValue() throws Exception {
        String token = token(UUID.randomUUID(), UserRole.INVENTORY_WORKER, "alice@example.com");

        HttpResponse<String> response = send("GET", "/inventory/stock", null, token, SPOOFED_HEADERS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(INVENTORY.single().header("X-User-Email")).containsExactly("alice@example.com");
    }

    @Test
    void tokenWithoutEmailClaim_isStillForwarded_withoutUserEmail() throws Exception {
        HttpResponse<String> response = send("GET", "/inventory/stock", null, token(UserRole.INVENTORY_WORKER));

        assertThat(response.statusCode()).isEqualTo(200);
        Received received = INVENTORY.single();
        assertThat(received.header("X-User-Id")).hasSize(1);
        assertThat(received.header("X-User-Email")).isEmpty();
    }

    @Test
    void publicRoute_dropsEmailClaim() throws Exception {
        String token = token(UUID.randomUUID(), UserRole.CUSTOMER, "alice@example.com");

        assertThat(send("POST", "/auth/login", "{}", token).statusCode()).isEqualTo(200);
        assertAnonymous(AUTH.single());
    }

    // --- Public ---

    @ParameterizedTest
    @ValueSource(strings = {"none", "valid", "garbage", "expired", "basic"})
    void publicRoute_forwardsEveryCallerAsAnonymous(String credentials) throws Exception {
        HttpResponse<String> response = send("POST", "/auth/login", "{}", null, authorization(credentials));

        assertThat(response.statusCode()).isEqualTo(200);
        assertAnonymous(AUTH.single());
    }

    // --- Optional ---

    @Test
    void optionalRoute_withoutToken_isAnonymous() throws Exception {
        assertThat(send("GET", "/catalog/products", null, null).statusCode()).isEqualTo(200);
        assertAnonymous(CATALOG.single());
    }

    @Test
    void optionalRoute_withValidToken_propagatesIdentity() throws Exception {
        UUID user = UUID.randomUUID();

        assertThat(send("GET", "/catalog/products", null, token(user, UserRole.CATALOG_MANAGER)).statusCode())
                .isEqualTo(200);

        Received received = CATALOG.single();
        assertThat(received.header("X-User-Id")).containsExactly(user.toString());
        assertThat(received.header("X-User-Role")).containsExactly("CATALOG_MANAGER");
        assertThat(received.header("Authorization")).isEmpty();
    }

    /** Deliberately strict: a client with a stale token gets 401 instead of silently becoming a guest. */
    @ParameterizedTest
    @ValueSource(strings = {"garbage", "expired", "basic", "empty-bearer"})
    void optionalRoute_withInvalidCredentials_isUnauthorized(String credentials) throws Exception {
        HttpResponse<String> response = send("GET", "/catalog/products", null, null, authorization(credentials));

        assertThat(response.statusCode()).isEqualTo(401);
        assertNoUpstreamCalled();
    }

    // --- Required ---

    @ParameterizedTest
    @ValueSource(strings = {"none", "garbage", "expired", "basic", "empty-bearer"})
    void requiredRoute_withoutValidToken_isUnauthorized(String credentials) throws Exception {
        HttpResponse<String> response = send("POST", "/user/non-customer", "{}", null, authorization(credentials));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"detail\":\"Authentication required\"");
        assertNoUpstreamCalled();
    }

    @Test
    void requiredRoute_withWrongRole_isForbiddenBeforeUpstream() throws Exception {
        HttpResponse<String> response = send("POST", "/user/non-customer", "{}", token(UserRole.CUSTOMER));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"detail\":\"Access denied\"");
        assertNoUpstreamCalled();
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        HttpResponse<String> response = send("POST", "/user/non-customer", "{}", null,
                "Authorization", "bearer " + adminToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(AUTH.single().header("X-User-Role")).containsExactly("ADMIN");
    }

    // --- Role matrix, generated from the route configuration ---

    @TestFactory
    List<DynamicTest> roleMatrix_everyProtectedRoute() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Sample sample : RouteSamples.from(gatewayProperties, policy)) {
            RouteAccess access = sample.access();
            if (access.roles().isEmpty()) {
                continue;
            }
            for (UserRole role : UserRole.values()) {
                boolean allowed = access.roles().contains(role);
                tests.add(DynamicTest.dynamicTest(sample.routeId() + " as " + role + " -> " + (allowed ? 200 : 403),
                        () -> {
                            resetStubs();
                            HttpResponse<String> response = send(sample.method(), sample.path(), "{}", token(role));
                            assertThat(response.statusCode()).isEqualTo(allowed ? 200 : 403);
                            if (allowed) {
                                assertThat(stub(sample.upstream()).single().header("X-User-Role"))
                                        .containsExactly(role.name());
                            } else {
                                assertNoUpstreamCalled();
                            }
                        }));
            }
            tests.add(DynamicTest.dynamicTest(sample.routeId() + " anonymous -> 401", () -> {
                resetStubs();
                assertThat(send(sample.method(), sample.path(), "{}", null).statusCode()).isEqualTo(401);
                assertNoUpstreamCalled();
            }));
        }
        assertThat(tests).isNotEmpty();
        return tests;
    }

    private static void assertAnonymous(Received received) {
        assertThat(received.header("X-User-Id")).isEmpty();
        assertThat(received.header("X-User-Role")).isEmpty();
        assertThat(received.header("X-User-Email")).isEmpty();
        assertThat(received.header("X-Internal-Token")).isEmpty();
        assertThat(received.header("Authorization")).isEmpty();
    }

    private static String[] authorization(String kind) {
        return switch (kind) {
            case "none" -> new String[0];
            case "valid" -> new String[]{"Authorization", "Bearer " + token(UserRole.ADMIN)};
            case "garbage" -> new String[]{"Authorization", "Bearer not.a.jwt"};
            case "expired" -> new String[]{"Authorization", "Bearer " + expiredToken()};
            case "basic" -> new String[]{"Authorization", "Basic dXNlcjpwYXNz"};
            case "empty-bearer" -> new String[]{"Authorization", "Bearer "};
            default -> throw new IllegalArgumentException(kind);
        };
    }
}
