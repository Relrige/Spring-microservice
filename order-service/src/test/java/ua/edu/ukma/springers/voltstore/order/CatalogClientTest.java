package ua.edu.ukma.springers.voltstore.order;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ua.edu.ukma.springers.voltstore.order.client.CatalogClient;
import ua.edu.ukma.springers.voltstore.order.client.ProductDto;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogClientTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("catalog.client.base-url",
                () -> wireMock.baseUrl() + "/mock/catalog/products");
    }

    @Autowired
    CatalogClient client;

    @Test
    void shouldFetchProduct() {
        UUID id = UUID.randomUUID();

        wireMock.stubFor(get(urlPathEqualTo("/mock/catalog/products/" + id))
                .withHeader("X-Correlation-Id", matching(".*")) // Interceptor works
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                {
                                  "id": "%s",
                                  "sku": "VOLT-001",
                                  "title": "Mock charger",
                                  "description": "Fast charger 65W",
                                  "category": "chargers",
                                  "price": 499.00,
                                  "deleted": false,
                                  "unknownField": "ігнорується (Tolerant Reader)"
                                }
                                """.formatted(id))));

        ProductDto dto = client.getById(id);

        assertEquals(id, dto.id());
        assertEquals("VOLT-001", dto.sku());
        assertEquals(0, new BigDecimal("499.00").compareTo(dto.price()));
    }

    @Test
    void shouldThrowOnReadTimeout() {
        UUID id = UUID.randomUUID();

        wireMock.stubFor(get(urlPathEqualTo("/mock/catalog/products/" + id+"/slow"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3500)
                        .withBody("{}")));

        assertThrows(ResourceAccessException.class,
                () -> client.getByIdSlow(id));
    }
}