package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.WireMockServer;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order's Catalog client behind a real network fault: a Toxiproxy container sits between the client and a stub
 * Catalog (WireMock on the host). A slow or cut connection must open the circuit breaker, after which calls fail fast
 * through the fallback without reaching Catalog, and the breaker must close again once Catalog is healthy.
 */
@SpringBootTest(classes = {
        ClientsConfiguration.class,
        CatalogClientAdapter.class,
        PaymentClientAdapter.class,
        DeliveryClientAdapter.class,
        AopAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        RetryAutoConfiguration.class,
        BulkheadAutoConfiguration.class
})
// A fresh HTTP client per scenario: a connection pooled through the proxy before disable() would be half-alive
// afterwards (a Toxiproxy artifact), which a restarted Catalog pod does not do
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CatalogCircuitBreakerToxiproxyTest {
    private static final String PATH = "/catalog/products/batch-get";
    private static final int PROXY_PORT = 8666;
    private static final List<UUID> IDS = List.of(UUID.randomUUID());

    private static final WireMockServer CATALOG = new WireMockServer(wireMockConfig().dynamicPort());
    // Docker Hub image: pulls without GitHub registry credentials (locally and on CI)
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("shopify/toxiproxy:2.1.4");
    private static final Proxy PROXY;

    static {
        CATALOG.start();
        // The container reaches the stub on the host as host.testcontainers.internal
        Testcontainers.exposeHostPorts(CATALOG.port());
        TOXIPROXY.start();
        try {
            PROXY = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("catalog", "0.0.0.0:" + PROXY_PORT, "host.testcontainers.internal:" + CATALOG.port());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the Toxiproxy proxy", e);
        }
    }

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        // Only Catalog goes through the proxy; the other clients are not called here
        registry.add("clients.catalog.url",
                () -> "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(PROXY_PORT));
        registry.add("clients.payment.url", CATALOG::baseUrl);
        registry.add("clients.delivery.url", CATALOG::baseUrl);
        registry.add("clients.catalog.read-timeout", () -> "500ms");
        registry.add("resilience4j.retry.configs.default.waitDuration", () -> "10ms");
        registry.add("resilience4j.retry.configs.default.enableExponentialBackoff", () -> "false");
        registry.add("resilience4j.retry.configs.default.enableRandomizedWait", () -> "false");
        // Production waits 10 s before probing a recovered Catalog; the test does not need to
        registry.add("resilience4j.circuitbreaker.configs.default.waitDurationInOpenState", () -> "1s");
    }

    @Autowired
    private ProductCatalog catalog;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private CircuitBreaker breaker;

    @BeforeEach
    void healthyCatalog() throws IOException {
        for (var toxic : PROXY.toxics().getAll()) {
            toxic.remove();
        }
        PROXY.enable();
        CATALOG.resetAll();
        CATALOG.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]")));
        breaker = circuitBreakers.circuitBreaker("catalog");
        breaker.reset();
    }

    @AfterAll
    static void stop() {
        TOXIPROXY.stop();
        CATALOG.stop();
    }

    @Test
    void slowNetwork_opensTheBreaker_thenCallsFailFastThroughTheFallback_andItClosesAfterRecovery() throws Exception {
        assertThat(catalog.findProducts(IDS)).isEmpty();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Every answer now arrives after 2 s, beyond the 500 ms read timeout
        PROXY.toxics().latency("slow-catalog", ToxicDirection.DOWNSTREAM, 2_000);
        failsAsCatalogUnavailable();
        failsAsCatalogUnavailable();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertCallIsShortCircuited();

        PROXY.toxics().get("slow-catalog").remove();
        assertRecovers();
    }

    @Test
    void cutConnection_opensTheBreaker_andItClosesAfterRecovery() throws Exception {
        // The proxy refuses connections, as if Catalog's pod had gone away
        PROXY.disable();
        failsAsCatalogUnavailable();
        failsAsCatalogUnavailable();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertCallIsShortCircuited();

        PROXY.enable();
        assertRecovers();
    }

    private void failsAsCatalogUnavailable() {
        assertThatThrownBy(() -> catalog.findProducts(IDS))
                .isInstanceOfSatisfying(DependencyUnavailableException.class,
                        e -> assertThat(e.getService()).isEqualTo("Catalog"));
    }

    /** OPEN: the fallback answers at once, without a network call. */
    private void assertCallIsShortCircuited() {
        int requestsBefore = CATALOG.getAllServeEvents().size();
        long started = System.nanoTime();

        failsAsCatalogUnavailable();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(300));
        assertThat(CATALOG.getAllServeEvents()).hasSize(requestsBefore);
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isPositive();
    }

    /** After the open-state wait, two trial calls in HALF_OPEN succeed and close the breaker. */
    private void assertRecovers() throws InterruptedException {
        Thread.sleep(1_100); // waitDurationInOpenState is 1 s in this test
        assertThat(catalog.findProducts(IDS)).isEmpty();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(catalog.findProducts(IDS)).isEmpty();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
