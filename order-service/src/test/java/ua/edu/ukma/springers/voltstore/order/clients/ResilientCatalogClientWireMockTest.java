package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {
                ClientsConfiguration.class,
                ResilientCatalogClient.class,
                AopAutoConfiguration.class,
                CircuitBreakerAutoConfiguration.class,
                RetryAutoConfiguration.class,
                BulkheadAutoConfiguration.class
        }
)
public class ResilientCatalogClientWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMockServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("clients.catalog.url", wireMockServer::baseUrl);

        // Speed up retries during tests
        registry.add("resilience4j.retry.instances.catalogClient.waitDuration", () -> "10ms");
        registry.add("resilience4j.retry.instances.catalogClient.enableRandomizedWait", () -> "false");
        registry.add("resilience4j.retry.instances.catalogClient.enableExponentialBackoff", () -> "false");

        // Lower bulkhead capacity for fast concurrent testing
        registry.add("resilience4j.bulkhead.instances.catalogClient.maxConcurrentCalls", () -> "2");
        registry.add("resilience4j.bulkhead.instances.catalogClient.maxWaitDuration", () -> "0");
    }

    @Autowired
    private ResilientCatalogClient resilientCatalogClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        circuitBreakerRegistry.circuitBreaker("catalogClient").reset();
    }

    private String productJson(UUID id, String title) {
        return """
                [
                    {
                        "id": "%s",
                        "title": "%s",
                        "price": 99.99,
                        "activenessStatus": "ACTIVE",
                        "stockStatus": "IN_STOCK"
                    }
                ]
                """.formatted(id, title);
    }

    @Test
    @DisplayName("Happy path: should return products when catalog service succeeds")
    void shouldReturnProductsWhenDownstreamSucceeds() {
        UUID productId = UUID.randomUUID();
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody(productJson(productId, "Smartphone"))));

        List<ProductDto> result = resilientCatalogClient.getProductsBatchSafely(List.of(productId));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo(productId);
        assertThat(result.getFirst().getTitle()).isEqualTo("Smartphone");

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/products/batch")));
        assertThat(circuitBreakerRegistry.circuitBreaker("catalogClient").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("CircuitBreaker: should open circuit breaker and activate fallback on error storm")
    void shouldOpenCircuitBreakerAndActivateFallbackOnErrorStorm() {
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .willReturn(aResponse()
                        .withStatus(500)));

        List<UUID> requestIds = List.of(UUID.randomUUID());
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("catalogClient");

        // minimumNumberOfCalls is 4, slidingWindowSize is 10; making 10 calls guarantees OPEN state
        for (int i = 0; i < 10; i++) {
            resilientCatalogClient.getProductsBatchSafely(requestIds);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        wireMockServer.resetRequests();
        List<ProductDto> fallbackResult = resilientCatalogClient.getProductsBatchSafely(requestIds);

        assertThat(fallbackResult).isEmpty();
        // When circuit breaker is OPEN, call should be short-circuited and never hit WireMock
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/products/batch")));
    }

    @Test
    @DisplayName("CircuitBreaker: should self-heal from HALF_OPEN to CLOSED when permitted calls succeed")
    void shouldRecoverFromHalfOpenToClosedWhenCallsSucceed() {
        UUID productId = UUID.randomUUID();
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody(productJson(productId, "Recovered Product"))));

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("catalogClient");
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // permittedNumberOfCallsInHalfOpenState is 2 in application.yml
        List<ProductDto> firstCall = resilientCatalogClient.getProductsBatchSafely(List.of(productId));
        List<ProductDto> secondCall = resilientCatalogClient.getProductsBatchSafely(List.of(productId));

        assertThat(firstCall).hasSize(1);
        assertThat(secondCall).hasSize(1);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Retry: should retry on transient failure and eventually succeed")
    void shouldRetryOnTransientFailureAndEventuallySucceed() {
        UUID productId = UUID.randomUUID();

        // 1st attempt fails with 500, transitions scenario to RETRIED
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .inScenario("RetryScenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("RETRIED")
                .willReturn(aResponse()
                        .withStatus(500)));

        // 2nd attempt succeeds with 200 OK
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .inScenario("RetryScenario")
                .whenScenarioStateIs("RETRIED")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody(productJson(productId, "Retry Success Product"))));

        List<ProductDto> result = resilientCatalogClient.getProductsBatchSafely(List.of(productId));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getTitle()).isEqualTo("Retry Success Product");

        // Verify that retry made 2 attempts before succeeding
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/products/batch")));
        assertThat(circuitBreakerRegistry.circuitBreaker("catalogClient").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Retry: should exhaust retries and activate fallback when all attempts fail")
    void shouldExhaustRetriesAndActivateFallbackWhenAllAttemptsFail() {
        UUID productId = UUID.randomUUID();
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .willReturn(aResponse()
                        .withStatus(500)));

        List<ProductDto> result = resilientCatalogClient.getProductsBatchSafely(List.of(productId));

        assertThat(result).isEmpty();
        // maxAttempts is 3 in application.yml: 1 initial call + 2 retries
        wireMockServer.verify(3, getRequestedFor(urlPathEqualTo("/products/batch")));
    }

    @Test
    @DisplayName("Bulkhead: should limit concurrent calls and activate fallback for rejected calls")
    void shouldLimitConcurrentCallsWithBulkhead() throws Exception {
        UUID productId = UUID.randomUUID();
        // Introduce delay so concurrent calls overlap
        wireMockServer.stubFor(get(urlPathEqualTo("/products/batch"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withFixedDelay(300)
                        .withBody(productJson(productId, "Delayed Product"))));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(3);

        List<List<ProductDto>> results = Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                startLatch.await();
                List<ProductDto> res = resilientCatalogClient.getProductsBatchSafely(List.of(productId));
                results.add(res);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        Thread t3 = new Thread(task);

        t1.start();
        t2.start();
        t3.start();

        // Release all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(results).hasSize(3);

        // maxConcurrentCalls is 2: exactly 2 succeed, 1 is rejected by bulkhead and falls back to empty list
        long emptyCount = results.stream().filter(List::isEmpty).count();
        long successCount = results.stream().filter(list -> !list.isEmpty()).count();

        assertThat(successCount).isEqualTo(2);
        assertThat(emptyCount).isEqualTo(1);
    }
}