package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.order.filters.CorrelationIdKeys;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * The four clients against one WireMock server on a random port, in a small context: the client configuration, the
 * adapters and the Resilience4j aspects, no database. Retries wait only a few milliseconds, and every circuit
 * breaker starts closed, so tests do not influence each other.
 */
@SpringBootTest(classes = {
        ClientsConfiguration.class,
        CatalogClientAdapter.class,
        InventoryClientAdapter.class,
        PaymentClientAdapter.class,
        DeliveryClientAdapter.class,
        AopAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        RetryAutoConfiguration.class,
        BulkheadAutoConfiguration.class
})
abstract class ClientsTestBase {
    static final String TOKEN = "internal-token-internal-token-1234567";
    static final String CORRELATION_ID = "corr-test-1";

    // One server for all client test classes: they share one cached Spring context, which keeps the URL it was
    // created with, so the port must not change between classes (a per-class extension would restart it)
    static final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        wireMock.start();
        WireMock.configureFor(wireMock.port());
    }

    @DynamicPropertySource
    static void clients(DynamicPropertyRegistry registry) {
        for (String service : new String[]{"catalog", "inventory", "payment", "delivery"}) {
            registry.add("clients." + service + ".url", wireMock::baseUrl);
        }
        registry.add("clients.catalog.read-timeout", () -> "500ms");
        registry.add("clients.inventory.read-timeout", () -> "500ms");
        registry.add("clients.delivery.read-timeout", () -> "500ms");
        registry.add("resilience4j.retry.configs.default.waitDuration", () -> "10ms");
        registry.add("resilience4j.retry.configs.default.enableExponentialBackoff", () -> "false");
        registry.add("resilience4j.retry.configs.default.enableRandomizedWait", () -> "false");
    }

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private BulkheadRegistry bulkheads;

    @BeforeEach
    void resetResilienceAndSetCorrelationId() {
        wireMock.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        MDC.put(CorrelationIdKeys.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
    }

    @AfterEach
    void clearCorrelationId() {
        MDC.remove(CorrelationIdKeys.CORRELATION_ID_MDC_KEY);
    }

    BulkheadRegistry bulkheads() {
        return bulkheads;
    }
}
