package ua.edu.ukma.springers.voltstore.delivery.clients;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.PackagingUnavailableException;
import ua.edu.ukma.springers.voltstore.delivery.services.ItemPackaging;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the Delivery → Inventory contract on EP-INV-05 (consumer-driven contract, Pact V4).
 * <p>
 * Each interaction is what Delivery's real client code ({@link InventoryPackagingProvider} on the production
 * {@link InventoryClient}) sends and needs back. The test runs it against Pact's mock server and writes the pact to
 * {@code contracts/pacts/delivery-service-inventory-service.json} (surefire property {@code pact.rootDir}); Inventory's
 * {@code PackagingPactProviderTest} verifies that file against the running application.
 * <p>
 * The pact holds only what Delivery reads: the four packaging fields by type, and the status codes of the failures.
 * The bodies of the {@code 404} and {@code 422} are not part of it, because Delivery ignores them. When this test
 * changes, run it and commit the regenerated pact in the same PR.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "inventory-service", pactVersion = PactSpecVersion.V4)
class InventoryPackagingPactTest {
    // Fixed ids: the provider states seed exactly these products
    static final UUID WITH_PACKAGING = UUID.fromString("6f1c2a4e-0000-4000-8000-000000000001");
    static final UUID UNKNOWN = UUID.fromString("6f1c2a4e-0000-4000-8000-000000000002");
    static final UUID WITHOUT_PACKAGING = UUID.fromString("6f1c2a4e-0000-4000-8000-000000000003");

    // Not a real secret: the pact requires some token, the provider test sends its own
    private static final String TOKEN = "pact-consumer-token";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    @Pact(consumer = "delivery-service")
    V4Pact packagingExists(PactDslWithProvider builder) {
        return builder
                .given("a stock item with complete packaging exists")
                .uponReceiving("a packaging request for a product with complete packaging")
                .method("GET")
                .path(path(WITH_PACKAGING))
                .matchHeader(TOKEN_HEADER, ".+", TOKEN)
                .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody()
                        .integerType("grossWeightGrams", 180)
                        .integerType("lengthCm", 22)
                        .integerType("widthCm", 12)
                        .integerType("heightCm", 6))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = "delivery-service")
    V4Pact noStockItem(PactDslWithProvider builder) {
        return builder
                .given("no stock item exists")
                .uponReceiving("a packaging request for a product without a stock item")
                .method("GET")
                .path(path(UNKNOWN))
                .matchHeader(TOKEN_HEADER, ".+", TOKEN)
                .willRespondWith()
                .status(404)
                .toPact(V4Pact.class);
    }

    @Pact(consumer = "delivery-service")
    V4Pact packagingMissing(PactDslWithProvider builder) {
        return builder
                .given("a stock item without packaging exists")
                .uponReceiving("a packaging request for a product whose packaging is not configured")
                .method("GET")
                .path(path(WITHOUT_PACKAGING))
                .matchHeader(TOKEN_HEADER, ".+", TOKEN)
                .willRespondWith()
                .status(422)
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "packagingExists")
    void completePackaging_isReadIntoItemPackaging(MockServer inventory) {
        List<ItemPackaging> packaging = provider(inventory).packagingFor(List.of(new ShipmentItem(WITH_PACKAGING, 2)));

        assertThat(packaging).containsExactly(new ItemPackaging(WITH_PACKAGING, 2, 180, 22, 12, 6));
    }

    @Test
    @PactTestFor(pactMethod = "noStockItem")
    void noStockItem_isNotRetryable(MockServer inventory) {
        assertThatThrownBy(() -> provider(inventory).packagingFor(List.of(new ShipmentItem(UNKNOWN, 1))))
                .isInstanceOfSatisfying(PackagingUnavailableException.class, e -> {
                    assertThat(e.getReason()).isEqualTo("stock_item_not_found:" + UNKNOWN);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    @PactTestFor(pactMethod = "packagingMissing")
    void missingPackaging_isNotRetryable(MockServer inventory) {
        assertThatThrownBy(() -> provider(inventory).packagingFor(List.of(new ShipmentItem(WITHOUT_PACKAGING, 1))))
                .isInstanceOfSatisfying(PackagingUnavailableException.class, e -> {
                    assertThat(e.getReason()).isEqualTo("packaging_data_missing:" + WITHOUT_PACKAGING);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    private static InventoryPackagingProvider provider(MockServer inventory) {
        InventoryClientProperties properties = new InventoryClientProperties(inventory.getUrl(), Duration.ofSeconds(5),
                Duration.ofSeconds(10));
        return new InventoryPackagingProvider(ClientsConfiguration.createInventoryClient(properties, TOKEN));
    }

    private static String path(UUID productId) {
        return "/inventory/stock/" + productId + "/packaging";
    }
}
