package ua.edu.ukma.springers.voltstore.delivery.clients;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observability task 02: a call to another service carries the W3C {@code traceparent} header of the current trace,
 * so the callee's spans join the same trace. Uses Boot's tracing auto-configuration with the OpenTelemetry bridge.
 */
@SpringBootTest(classes = TracePropagationTest.Empty.class,
        properties = "management.tracing.sampling.probability=1.0")
@ImportAutoConfiguration({
        ObservationAutoConfiguration.class,
        MicrometerTracingAutoConfiguration.class,
        OpenTelemetrySdkAutoConfiguration.class,
        OpenTelemetryTracingAutoConfiguration.class
})
class TracePropagationTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";
    private static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @BeforeAll
    static void start() {
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @Test
    void callToAnotherService_carriesTheCurrentTraceIdInTraceparent() {
        UUID productId = UUID.randomUUID();
        String path = "/inventory/stock/" + productId + "/packaging";
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(okJson("{\"grossWeightGrams\":1,\"lengthCm\":1,\"widthCm\":1,\"heightCm\":1}")));
        InventoryClient client = ClientsConfiguration.createInventoryClient(
                new InventoryClientProperties(wireMock.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1)), TOKEN, observationRegistry);
        AtomicReference<String> traceId = new AtomicReference<>();

        Observation.createNotStarted("test.request", observationRegistry).observe(() -> {
            traceId.set(tracer.currentSpan().context().traceId());
            client.getPackaging(productId);
        });

        assertThat(traceId.get()).matches("[0-9a-f]{32}");
        // 00-<trace id>-<client span id>-<flags>; flags 01 = sampled, OpenTelemetry also sets 02 (random trace id)
        wireMock.verify(getRequestedFor(urlPathEqualTo(path))
                .withHeader("traceparent", matching("00-" + traceId.get() + "-[0-9a-f]{16}-0[13]")));
    }

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }
}
