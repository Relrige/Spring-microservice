package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = {
                ClientsConfiguration.class,
                CatalogClientResilienceTest.TestConfig.class
        },
        properties = "clients.catalog.url=http://localhost:8081"
)
@WireMockTest(httpPort = 8081)// 1. Вмикаємо підтримку Testcontainers
class CatalogClientResilienceTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    // 2. Магія Spring Boot! Піднімаємо реальний PostgreSQL у Docker спеціально для тесту.
    // @ServiceConnection автоматично передасть URL, логін та пароль у Spring контекст.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    // 3. Запускаємо нативний WireMock сервер
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    // 4. Динамічно передаємо адресу WireMock у властивості нашого RestClient
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("clients.catalog.url", wireMock::baseUrl);
    }

    @Autowired
    private ResilientCatalogClient resilientCatalogClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    private CatalogClient catalogClient;

    @Test
    void shouldOpenCircuitBreakerAndTriggerFallbackDuringErrorStorm() {

        // Симулюємо шторм помилок: Каталог завжди відповідає 500 Internal Server Error
        wireMock.stubFor(get(urlPathMatching("/api/v1/catalog/products/batch.*"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")));

        List<UUID> requestIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        // Робимо 5 запитів. Оскільки minimumNumberOfCalls = 4,
        // на п'ятому запиті Circuit Breaker гарантовано відкриється.
        for (int i = 0; i < 5; i++) {
            List<ProductDto> result = resilientCatalogClient.getProductsBatchSafely(requestIds);

            // Перевіряємо, що спрацював Fallback і повернув пустий список (безпечне значення)
            assertTrue(result.isEmpty(), "Fallback should return an empty list");
        }

        // Перевіряємо стан Circuit Breaker
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("catalogClient");

        // Переконуємося, що ланцюг розімкнено (OPEN)
        assertEquals(CircuitBreaker.State.OPEN, cb.getState(),
                "Circuit Breaker should be in OPEN state after error storm");
    }
}