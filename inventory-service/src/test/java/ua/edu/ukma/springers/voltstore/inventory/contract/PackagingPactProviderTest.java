package ua.edu.ukma.springers.voltstore.inventory.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.CatalogKnowsEveryProduct;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.UUID;

/**
 * Provider side of the Delivery → Inventory contract on EP-INV-05: replays every interaction of the committed pact
 * {@code contracts/pacts/delivery-service-inventory-service.json} against the running application (real security
 * filters, controller, service and PostgreSQL) and fails when Inventory's answer no longer matches what Delivery
 * expects.
 * <p>
 * Order of work: the pact is written by Delivery's {@code InventoryPackagingPactTest}. When Delivery's expectations
 * change, run Delivery's tests and commit the regenerated pact in the same PR; this test always verifies the
 * committed file, in CI too.
 * <p>
 * The product ids of the provider states are the fixed ids of the consumer test.
 */
@Provider("inventory-service")
@PactFolder("../contracts/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, CatalogKnowsEveryProduct.class})
class PackagingPactProviderTest {
    private static final UUID WITH_PACKAGING = UUID.fromString("6f1c2a4e-0000-4000-8000-000000000001");
    private static final UUID WITHOUT_PACKAGING = UUID.fromString("6f1c2a4e-0000-4000-8000-000000000003");

    @LocalServerPort
    private int port;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
        // Each interaction starts from an empty table; a RANDOM_PORT server commits, so no transactional rollback
        jdbcTemplate.update("DELETE FROM stock_item_reservations");
        jdbcTemplate.update("DELETE FROM stock_items");
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void verifyInteraction(PactVerificationContext context, ClassicHttpRequest request) {
        // The pact only requires some token; send the one this test configuration accepts
        request.setHeader("X-Internal-Token", TrustedHeaders.INTERNAL_TOKEN);
        context.verifyInteraction();
    }

    @State("a stock item with complete packaging exists")
    void stockItemWithPackaging() {
        stockService.setPackaging(WITH_PACKAGING, 180, 22, 12, 6);
    }

    @State("no stock item exists")
    void noStockItem() {
        // The table is already empty
    }

    @State("a stock item without packaging exists")
    void stockItemWithoutPackaging() {
        stockService.adjustStock(WITHOUT_PACKAGING, 3);
    }
}
