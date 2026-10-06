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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ua.edu.ukma.springers.voltstore.order.utils.constants.CorrelationIdKeys;

@WireMockTest(httpPort = 8081)
@SpringBootTest(
        classes = {
                ClientsConfiguration.class
        },
        properties = "clients.catalog.url=http://localhost:8081"
)
class CatalogClientWireMockTest {

    private static final Logger log = LoggerFactory.getLogger(CatalogClientWireMockTest.class);

    @Autowired
    private CatalogClient catalogClient;

    @Test
    void getProductsByIds_ShouldReturnData_WhenHappyPath() {
        MDC.put(CorrelationIdKeys.CORRELATION_ID_MDC_KEY, "test-corr-id-happy-path");
        try {
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
            log.info("Executing client call for product IDs: [{}]", productId);
            List<ProductDto> products = catalogClient.getProductsByIds(List.of(productId));
            log.info("<<< [SUCCESS] Response received successfully: {}", products);

            // Assert
            assertNotNull(products);
            assertEquals(1, products.size());
            assertEquals(productId, products.get(0).getId());
            assertEquals("Test Product", products.get(0).getTitle());
        } finally {
            MDC.clear();
        }
    }

    @Test
    void getProductsByIds_ShouldThrowResourceAccessException_WhenReadTimeoutExceeded() {
        MDC.put(CorrelationIdKeys.CORRELATION_ID_MDC_KEY, "test-corr-id-timeout");
        try {
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
            log.info("Executing client call expecting timeout (configured timeout = 3s, delay = 3.5s)...");
            ResourceAccessException exception = assertThrows(ResourceAccessException.class, () -> {
                catalogClient.getProductsByIds(List.of(productId));
            });

            log.warn("<<< [TIMEOUT TRIGGERED] ResourceAccessException thrown as expected: {}", exception.getMessage());
            assertNotNull(exception.getCause());
        } finally {
            MDC.clear();
        }
    }
}
