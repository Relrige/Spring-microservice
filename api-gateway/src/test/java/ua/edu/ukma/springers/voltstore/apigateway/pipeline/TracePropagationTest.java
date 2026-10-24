package ua.edu.ukma.springers.voltstore.apigateway.pipeline;

import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.apigateway.support.GatewayIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability task 02: the gateway is where a request's trace starts. It forwards the W3C {@code traceparent}
 * header to the routed service, so the service's spans join the gateway's trace; an incoming header is continued.
 */
class TracePropagationTest extends GatewayIntegrationTest {
    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-0[13]";

    @Test
    void requestWithoutTraceparent_startsATraceAndForwardsIt() throws Exception {
        assertThat(send("GET", "/catalog/categories", null, null).statusCode()).isEqualTo(200);

        List<String> forwarded = CATALOG.single().header("traceparent");
        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.getFirst()).matches(TRACEPARENT);
    }

    @Test
    void incomingTraceparent_isContinued_underTheGatewaysOwnSpan() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String callerSpan = "00f067aa0ba902b7";

        send("GET", "/catalog/categories", null, null, "traceparent", "00-" + traceId + "-" + callerSpan + "-01");

        String forwarded = CATALOG.single().header("traceparent").getFirst();
        assertThat(forwarded).matches(TRACEPARENT).startsWith("00-" + traceId + "-");
        assertThat(forwarded).as("the parent is the gateway's span, not the caller's")
                .doesNotContain(callerSpan);
    }
}
