package ua.edu.ukma.springers.voltstore.order.clients;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.test.context.TestPropertySource;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability task 02: a call to another service carries the W3C {@code traceparent} header of the current trace,
 * so the callee's spans join the same trace. Uses Boot's tracing auto-configuration with the OpenTelemetry bridge.
 */
@ImportAutoConfiguration({
        ObservationAutoConfiguration.class,
        MicrometerTracingAutoConfiguration.class,
        OpenTelemetrySdkAutoConfiguration.class,
        OpenTelemetryTracingAutoConfiguration.class
})
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class TracePropagationTest extends ClientsTestBase {
    private static final String PATH = "/catalog/products/batch-get";

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private ProductCatalog catalog;

    @Test
    void callToAnotherService_carriesTheCurrentTraceIdInTraceparent() {
        stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]")));
        AtomicReference<String> traceId = new AtomicReference<>();

        Observation.createNotStarted("test.request", observationRegistry).observe(() -> {
            traceId.set(tracer.currentSpan().context().traceId());
            catalog.findProducts(List.of(UUID.randomUUID()));
        });

        assertThat(traceId.get()).matches("[0-9a-f]{32}");
        // 00-<trace id>-<client span id>-<flags>: the callee continues this trace under the client span. Flags 01 =
        // sampled; OpenTelemetry also sets 02 (W3C level 2 "random trace id"), so 03 is expected as well
        verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("traceparent", matching("00-" + traceId.get() + "-[0-9a-f]{16}-0[13]")));
    }
}
