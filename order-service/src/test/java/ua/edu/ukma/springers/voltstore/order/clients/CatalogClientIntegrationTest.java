package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.ResourceAccessException;
import ua.edu.ukma.springers.voltstore.order.clients.dto.ProductDto;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = {
                ClientsConfiguration.class,
                CatalogClientIntegrationTest.TestConfig.class
        },
        properties = "clients.catalog.url=http://localhost:8081"
)
@WireMockTest(httpPort = 8081)
class CatalogClientIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    @Autowired
    private CatalogClient catalogClient;

    @Test
    void getProductsByIds_ShouldReturnData_WhenHappyPath() {
        // Arrange
        UUID productId = UUID.randomUUID();
        
        stubFor(get(urlPathEqualTo("/products/batch"))
                .withQueryParam("ids", equalTo(productId.toString()))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withStatus(200)
                        .withBody("""
                                [
                                    {
                                        "id": "%s",
                                        "title": "Test Product",
                                        "price": 99.99,
                                        "activenessStatus": "ACTIVE",
                                        "stockStatus": "IN_STOCK"
                                    }
                                ]
                                """.formatted(productId))));

        // Act
        List<ProductDto> products = catalogClient.getProductsByIds(List.of(productId));

        // Assert
        assertNotNull(products);
        assertEquals(1, products.size());
        assertEquals(productId, products.get(0).getId());
        assertEquals("Test Product", products.get(0).getTitle());
    }

    @Test
    void getProductsByIds_ShouldThrowResourceAccessException_WhenReadTimeoutExceeded() {
        // Arrange
        UUID productId = UUID.randomUUID();

        // Stub a response with a 3.5-second delay to trigger the 3-second read timeout
        stubFor(get(urlPathEqualTo("/products/batch"))
                .withQueryParam("ids", equalTo(productId.toString()))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3500) 
                        .withBody("[]")));

        // Act & Assert
        ResourceAccessException exception = assertThrows(ResourceAccessException.class, () -> {
            catalogClient.getProductsByIds(List.of(productId));
        });

        assertNotNull(exception.getCause());
    }
}
