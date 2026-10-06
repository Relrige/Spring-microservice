package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;

import java.util.List;
import java.util.UUID;

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
    }

    @Autowired
    private ResilientCatalogClient resilientCatalogClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry.circuitBreaker("catalogClient").transitionToClosedState();
    }

    @Test
    void shouldOpenCircuitBreakerAndActivateFallbackOnErrorStorm() {
        wireMockServer.stubFor(get(urlPathMatching("/products/batch"))
                .willReturn(aResponse()
                        .withStatus(500)));

        List<UUID> requestIds = List.of(UUID.randomUUID());
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("catalogClient");

        for (int i = 0; i < 1000; i++) {
            resilientCatalogClient.getProductsBatchSafely(requestIds);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        wireMockServer.resetRequests();
        List<ProductDto> fallbackResult = resilientCatalogClient.getProductsBatchSafely(requestIds);

        assertThat(fallbackResult).isEmpty();
        wireMockServer.verify(0, getRequestedFor(urlPathMatching("/api/v1/catalog/products/batch.*")));
    }
}