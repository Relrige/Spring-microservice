package ua.edu.ukma.springers.voltstore.apigateway.errors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;
import ua.edu.ukma.springers.voltstore.apigateway.support.StubUpstream;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static ua.edu.ukma.springers.voltstore.apigateway.errors.ErrorResponsesTest.assertProblem;

/**
 * Upstreams that cannot be reached or answer too slowly. Separate context: short read timeout, an upstream on a
 * closed port (like a service that is not deployed) and one with an unresolvable host name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.http.clients.read-timeout=500ms")
class UpstreamFailureTest {
    private static final StubUpstream SLOW_AUTH = new StubUpstream("auth");

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("gateway.upstreams.auth", SLOW_AUTH::url);
        registry.add("gateway.upstreams.catalog", () -> "http://catalog-service.invalid:8081");
        registry.add("gateway.upstreams.inventory", () -> "http://127.0.0.1:" + closedPort());
    }

    @AfterAll
    static void resetStub() {
        SLOW_AUTH.reset();
    }

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void connectionRefused_isBadGateway() throws Exception {
        HttpResponse<String> response = send("GET", "/inventory/stock",
                GatewayIntegrationTest.token(UserRole.INVENTORY_WORKER));

        assertProblem(response, 502, "bad-gateway", "Bad Gateway", "The upstream service is unavailable.");
    }

    @Test
    void unknownHost_isBadGateway() throws Exception {
        HttpResponse<String> response = send("GET", "/catalog/categories", null);

        assertProblem(response, 502, "bad-gateway", "Bad Gateway", "The upstream service is unavailable.");
    }

    @Test
    void slowUpstream_isGatewayTimeout() throws Exception {
        SLOW_AUTH.replyAfter(Duration.ofSeconds(3));

        HttpResponse<String> response = send("POST", "/auth/login", null);

        assertProblem(response, 504, "gateway-timeout", "Gateway Timeout",
                "The upstream service did not respond in time.");
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
