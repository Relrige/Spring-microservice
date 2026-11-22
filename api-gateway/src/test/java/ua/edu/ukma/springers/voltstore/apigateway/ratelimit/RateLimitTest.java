package ua.edu.ukma.springers.voltstore.apigateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Small limits, so a few requests exceed them; a fresh context per test starts every client at zero. */
@TestPropertySource(properties = {
        "gateway.rate-limit.enabled=true",
        "gateway.rate-limit.auth.requests=2",
        "gateway.rate-limit.auth.period=1m",
        "gateway.rate-limit.general.requests=3",
        "gateway.rate-limit.general.period=1m"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitTest extends GatewayIntegrationTest {
    private static final String LOGIN = "{\"email\":\"a@example.com\",\"password\":\"secret123\"}";

    @Autowired
    private MeterRegistry meters;

    @Test
    void login_overTheAuthLimit_is429WithRetryAfter_andNeverReachesAuth() throws Exception {
        assertThat(send("POST", "/auth/login", LOGIN, null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/auth/login", LOGIN, null).statusCode()).isEqualTo(200);

        HttpResponse<String> limited = send("POST", "/auth/login", LOGIN, null);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(limited.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(limited.body())
                .contains("\"type\":\"https://voltstore.com/errors/too-many-requests\"")
                .contains("\"status\":429")
                .contains("\"service\":\"api-gateway\"");
        assertThat(AUTH.received()).hasSize(2);
    }

    @Test
    void registration_sharesTheAuthLimit() throws Exception {
        send("POST", "/user/register", "{}", null);
        send("POST", "/auth/login", LOGIN, null);

        assertThat(send("POST", "/user/register", "{}", null).statusCode()).isEqualTo(429);
    }

    @Test
    void otherRoutes_overTheGeneralLimit_are429_whileLoginStillWorks() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(send("GET", "/catalog/products", null, null).statusCode()).isEqualTo(200);
        }

        assertThat(send("GET", "/catalog/products", null, null).statusCode()).isEqualTo(429);
        assertThat(CATALOG.received()).hasSize(3);
        assertThat(send("POST", "/auth/login", LOGIN, null).statusCode()).isEqualTo(200);
    }

    @Test
    void limitIsCheckedBeforeTheToken_soAnInvalidTokenOverTheLimitIs429() throws Exception {
        for (int i = 0; i < 3; i++) {
            send("GET", "/catalog/products", null, "not-a-jwt");
        }

        assertThat(send("GET", "/catalog/products", null, "not-a-jwt").statusCode()).isEqualTo(429);
    }

    @Test
    void rejections_areCountedPerLimit() throws Exception {
        for (int i = 0; i < 4; i++) {
            send("POST", "/auth/login", LOGIN, null);
        }

        assertThat(meters.get(RateLimitFilter.REJECTED_METRIC).tag("limit", "auth").counter().count()).isEqualTo(2);
        assertThat(meters.get(RateLimitFilter.REJECTED_METRIC).tag("limit", "general").counter().count()).isZero();
    }
}
