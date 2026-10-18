package ua.edu.ukma.springers.voltstore.apigateway.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;
import ua.edu.ukma.springers.voltstore.apigateway.support.StubUpstream.Received;

import java.net.http.HttpResponse;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTableTest extends GatewayIntegrationTest {
    private static final String ID = "3f2c6a52-7b1e-4c55-9d0a-6f1e2b3c4d5e";

    @Autowired
    RouteAccessPolicy policy;

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "POST,   /user/register,                         auth",
            "POST,   /auth/login,                            auth",
            "POST,   /user/non-customer,                     auth",
            "GET,    /catalog/products,                      catalog",
            "GET,    /catalog/products/" + ID + ",           catalog",
            "POST,   /catalog/products,                      catalog",
            "PUT,    /catalog/products/" + ID + ",           catalog",
            "PATCH,  /catalog/products/" + ID + "/status,    catalog",
            "GET,    /catalog/categories,                    catalog",
            "POST,   /catalog/categories,                    catalog",
            "PUT,    /catalog/categories/" + ID + ",         catalog",
            "DELETE, /catalog/categories/" + ID + ",         catalog",
            "GET,    /inventory/stock,                       inventory",
            "POST,   /inventory/stock/" + ID + "/adjust,     inventory",
            "PUT,    /inventory/stock/" + ID + "/packaging,  inventory",
            "GET,    /customers/me/profile,                  customer",
            "PUT,    /customers/me/profile,                  customer",
            "GET,    /customers/cart,                        customer",
            "PUT,    /customers/cart,                        customer",
            "DELETE, /customers/cart,                        customer",
            "POST,   /customers/cart/merge,                  customer",
            "POST,   /customers/cart/items,                  customer",
            "PATCH,  /customers/cart/items/" + ID + ",       customer",
            "DELETE, /customers/cart/items/" + ID + ",       customer",
            "POST,   /delivery/orders/" + ID + "/ttn/retry,  delivery"
    })
    void routedMethodAndPath_reachesItsUpstreamUnchanged(String method, String path, String upstream)
            throws Exception {
        HttpResponse<String> response = send(method, path + "?a=1", "{\"x\":1}", tokenAllowedOn(method, path));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("stub:" + upstream);
        Received received = stub(upstream).single();
        assertThat(received.method()).isEqualTo(method);
        assertThat(received.uri()).isEqualTo(path + "?a=1");
        assertThat(received.body()).isEqualTo("{\"x\":1}");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,    /user/register",
            "PUT,    /auth/login",
            "DELETE, /user/non-customer",
            "GET,    /user/non-customer",
            "PATCH,  /user/register",
            "DELETE, /catalog/products/" + ID,
            "PUT,    /catalog/categories",
            "GET,    /catalog/categories/" + ID,
            "POST,   /inventory/stock",
            "GET,    /inventory/stock/" + ID + "/adjust",
            "POST,   /customers/cart",
            "DELETE, /customers/me/profile",
            "GET,    /customers/cart/items/" + ID,
            "GET,    /delivery/orders/" + ID + "/ttn/retry"
    })
    void unlistedMethodOnRoutedPath_isNotFound(String method, String path) throws Exception {
        assertThat(send(method, path, "", adminToken()).statusCode()).isEqualTo(404);
        assertNoUpstreamCalled();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  /catalog/products/batch-get",
            "GET,  /inventory/stock/" + ID + "/readiness",
            "GET,  /inventory/stock/" + ID + "/packaging",
            "POST, /inventory/reservations",
            "POST, /inventory/reservations/" + ID + "/release",
            "POST, /payments/charge",
            "POST, /payments/refund",
            "GET,  /delivery/shipments",
            "DELETE, /delivery/shipments/" + ID
    })
    void internalEndpoint_isNotFound(String method, String path) throws Exception {
        for (UserRole role : UserRole.values()) {
            assertThat(send(method, path, "{}", token(role)).statusCode()).isEqualTo(404);
        }
        assertThat(send(method, path, "{}", null).statusCode()).isEqualTo(404);
        assertNoUpstreamCalled();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,    /catalog/products/not-a-uuid",
            "GET,    /catalog/products/3f2c6a52-7b1e-4c55-9d0a-6f1e2b3c4d5",
            "GET,    /catalog/products/3f2c6a527b1e4c559d0a6f1e2b3c4d5e",
            "PUT,    /catalog/products/123",
            "PATCH,  /catalog/products/x/status",
            "DELETE, /catalog/categories/batch-get",
            "POST,   /inventory/stock/abc/adjust",
            "PUT,    /inventory/stock/1-1-1-1-1/packaging",
            "PATCH,  /customers/cart/items/not-a-uuid",
            "DELETE, /customers/cart/items/123",
            "POST,   /delivery/orders/abc/ttn/retry"
    })
    void nonUuidId_isNotFound(String method, String path) throws Exception {
        assertThat(send(method, path, "{}", adminToken()).statusCode()).isEqualTo(404);
        assertNoUpstreamCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/user", "/user/other", "/user/register/", "/user/register/extra", "/auth/login/x", "/auth",
            "/catalog", "/catalog/products/", "/inventory", "/orders", "/customers", "/customers/cart/",
            "/customers/me", "/delivery", "/delivery/orders",
            "/actuator/env", "/UNKNOWN"
    })
    void unroutedPath_isNotFound(String path) throws Exception {
        assertThat(send("POST", path, "{}", null).statusCode()).isEqualTo(404);
        assertThat(send("GET", path, "", null).statusCode()).isEqualTo(404);
        assertNoUpstreamCalled();
    }

    @Test
    void accessRulesAreParsedFromMetadata() {
        assertThat(policy.all()).hasSize(24);
        assertThat(policy.forRoute("auth-register")).contains(new RouteAccess(AuthMode.PUBLIC, Set.of()));
        assertThat(policy.forRoute("catalog-browse-products")).contains(new RouteAccess(AuthMode.OPTIONAL, Set.of()));
        assertThat(policy.forRoute("auth-create-non-customer"))
                .contains(new RouteAccess(AuthMode.REQUIRED, Set.of(UserRole.ADMIN)));
        assertThat(policy.forRoute("inventory-adjust-stock"))
                .contains(new RouteAccess(AuthMode.REQUIRED, Set.of(UserRole.INVENTORY_WORKER)));
        assertThat(policy.forRoute("customer-update-cart-item"))
                .contains(new RouteAccess(AuthMode.REQUIRED, Set.of(UserRole.CUSTOMER)));
        assertThat(policy.forRoute("delivery-retry-ttn"))
                .contains(new RouteAccess(AuthMode.REQUIRED, Set.of(UserRole.INVENTORY_WORKER)));
        assertThat(policy.forRoute("unknown")).isEmpty();
    }

    /** A token whose role is allowed on the route, or none if the route does not require one. */
    private String tokenAllowedOn(String method, String path) {
        if (path.startsWith("/user/non-customer")) {
            return adminToken();
        }
        if (path.startsWith("/customers")) {
            return token(UserRole.CUSTOMER);
        }
        if (path.startsWith("/inventory") || path.startsWith("/delivery")) {
            return token(UserRole.INVENTORY_WORKER);
        }
        if (path.startsWith("/catalog") && !method.equals("GET")) {
            return token(UserRole.CATALOG_MANAGER);
        }
        return null;
    }
}
