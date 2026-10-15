package ua.edu.ukma.springers.voltstore.inventory.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ua.edu.ukma.springers.voltstore.inventory.services.ProductCatalog;

/**
 * For integration tests that are not about the Catalog call: every product exists, so adjust and packaging create
 * StockItems on demand without a running Catalog. The Catalog call itself is tested against WireMock
 * ({@code CatalogExistenceCheckIntegrationTest}, {@code CatalogProductCatalogTest}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class CatalogKnowsEveryProduct {
    @Bean
    @Primary
    ProductCatalog everyProductExists() {
        return productId -> true;
    }
}
