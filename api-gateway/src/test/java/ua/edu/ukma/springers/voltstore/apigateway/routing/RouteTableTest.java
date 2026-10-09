package ua.edu.ukma.springers.voltstore.apigateway.routing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RouteTableTest {

    record Received(String method, String uri, String body) {
    }

    private static HttpServer authStub;
    private static final List<Received> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws IOException {
        authStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authStub.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
            byte[] response = "stub".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        authStub.start();
    }

    @AfterAll
    static void stopStub() {
        authStub.stop(0);
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstreams.auth", () -> "http://127.0.0.1:" + authStub.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    RouteAccessPolicy policy;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        received.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /user/register",
            "POST, /auth/login",
            "POST, /user/non-customer"
    })
    void routedMethodAndPath_reachesAuthServiceUnchanged(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path + "?a=1", "{\"x\":1}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("stub");
        assertThat(received).containsExactly(new Received(method, path + "?a=1", "{\"x\":1}"));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /user/register",
            "PUT, /auth/login",
            "DELETE, /user/non-customer",
            "GET, /user/non-customer",
            "PATCH, /user/register"
    })
    void unlistedMethodOnRoutedPath_isNotFound(String method, String path) throws Exception {
        assertThat(send(method, path, "").statusCode()).isEqualTo(404);
        assertThat(received).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/user", "/user/other", "/user/register/", "/user/register/extra", "/auth/login/x", "/auth",
            "/payments/charge", "/actuator/env", "/UNKNOWN"
    })
    void unroutedPath_isNotFound(String path) throws Exception {
        assertThat(send("POST", path, "{}").statusCode()).isEqualTo(404);
        assertThat(received).isEmpty();
    }

    @Test
    void everyRouteHasAnAccessRule() {
        assertThat(policy.all()).containsOnlyKeys("auth-register", "auth-login", "auth-create-non-customer");
        assertThat(policy.forRoute("auth-register")).contains(new RouteAccess(AuthMode.PUBLIC, Set.of()));
        assertThat(policy.forRoute("auth-login")).contains(new RouteAccess(AuthMode.PUBLIC, Set.of()));
        assertThat(policy.forRoute("auth-create-non-customer"))
                .contains(new RouteAccess(AuthMode.REQUIRED, Set.of(UserRole.ADMIN)));
        assertThat(policy.forRoute("unknown")).isEmpty();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
