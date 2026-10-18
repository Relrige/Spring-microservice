package ua.edu.ukma.springers.voltstore.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Probes answer on the management port only; on the public port Actuator does not exist. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "gateway.upstreams.auth=http://127.0.0.1:1",
        "gateway.upstreams.catalog=http://127.0.0.1:1",
        "gateway.upstreams.inventory=http://127.0.0.1:1",
        "gateway.upstreams.customer=http://127.0.0.1:1",
        "gateway.upstreams.delivery=http://127.0.0.1:1",
        "gateway.upstreams.order=http://127.0.0.1:1"
})
class ManagementPortTest {

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void probes_areServedOnManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void actuator_isNotFoundOnPublicPort() throws Exception {
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(int targetPort, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
