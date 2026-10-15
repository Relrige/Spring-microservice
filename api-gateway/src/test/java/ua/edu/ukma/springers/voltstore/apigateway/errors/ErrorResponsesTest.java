package ua.edu.ukma.springers.voltstore.apigateway.errors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponsesTest extends GatewayIntegrationTest {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,    /no/such/route",
            "DELETE, /auth/login",
            "GET,    /catalog/products/batch-get",
            "GET,    /actuator/env"
    })
    void noRoute_isProblemNotFound(String method, String path) throws Exception {
        HttpResponse<String> response = send(method, path, null, null);

        assertProblem(response, 404, "not-found", "Not Found", "No route matches the request.");
    }

    @Test
    void unauthorized_isProblemWithFixedDetail() throws Exception {
        HttpResponse<String> response = send("POST", "/user/non-customer", "{}", expiredToken());

        assertProblem(response, 401, "unauthorized", "Unauthorized", "Authentication required");
        assertThat(response.body()).doesNotContainIgnoringCase("expired");
    }

    @Test
    void forbidden_isProblemWithFixedDetail() throws Exception {
        HttpResponse<String> response = send("POST", "/catalog/products", "{}", token(UserRole.CUSTOMER));

        assertProblem(response, 403, "forbidden", "Forbidden", "Access denied");
    }

    @ParameterizedTest(name = "upstream {0}")
    @CsvSource(delimiter = '|', value = {
            "409 | application/problem+json | {\"status\":409,\"detail\":\"Email is already registered\",\"service\":\"auth-service\"}",
            "401 | application/problem+json | {\"status\":401,\"detail\":\"Invalid credentials\",\"service\":\"auth-service\"}",
            "400 | application/problem+json | {\"status\":400,\"errors\":{\"email\":[\"must be valid\"]}}",
            "500 | application/problem+json | {\"status\":500,\"service\":\"auth-service\"}",
            "201 | application/json         | {\"userId\":\"x\"}"
    })
    void upstreamResponse_isPassedThroughUnchanged(int status, String contentType, String body) throws Exception {
        AUTH.replyWith(status, contentType, body);

        HttpResponse<String> response = send("POST", "/user/register", "{}", null);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains(contentType);
        assertThat(response.body()).isEqualTo(body);
    }

    @Test
    void unexpectedError_isGenericProblem() {
        var response = new GatewayExceptionHandler()
                .handleAllOtherExceptions(new IllegalStateException("internal detail http://catalog-service:8081"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(response.getBody().getProperties()).containsEntry("service", "api-gateway");
    }

    static void assertProblem(HttpResponse<String> response, int status, String type, String title, String detail) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(response.body())
                .contains("\"type\":\"https://voltstore.com/errors/" + type + "\"")
                .contains("\"title\":\"" + title + "\"")
                .contains("\"status\":" + status)
                .contains("\"detail\":\"" + detail + "\"")
                .contains("\"timestamp\":\"")
                .contains("\"service\":\"api-gateway\"")
                .doesNotContain("trace")
                .doesNotContain("127.0.0.1");
    }
}
