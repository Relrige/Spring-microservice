package ua.edu.ukma.springers.voltstore.delivery.clients;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.PackagingUnavailableException;
import ua.edu.ukma.springers.voltstore.delivery.services.ItemPackaging;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against a fake Inventory (WireMock), without a Spring context: the client is built exactly as in
 * production, with a short read timeout so the timeout test stays fast.
 */
class InventoryPackagingProviderTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID productId = UUID.randomUUID();

    // The very first call loads the HTTP client and JSON classes, which can take longer than the short read
    // timeout; do it once here so no test fails for a cold JVM
    @BeforeAll
    static void warmUp() {
        inventory.stubFor(get(anyUrl()).willReturn(okJson(packagingJson())));
        InventoryClientProperties relaxed = new InventoryClientProperties(inventory.baseUrl(), Duration.ofSeconds(5),
                Duration.ofSeconds(30));
        new InventoryPackagingProvider(ClientsConfiguration.createInventoryClient(relaxed, TOKEN))
                .packagingFor(List.of(new ShipmentItem(UUID.randomUUID(), 1)));
        inventory.resetAll();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void ok_returnsPackagingPerItem_andSendsTheTokenButNoUserHeaders() {
        stub(okJson("""
                {"grossWeightGrams":180,"lengthCm":22,"widthCm":12,"heightCm":6,"futureField":true}"""));

        List<ItemPackaging> packaging = provider(inventory.baseUrl()).packagingFor(List.of(item(2)));

        assertThat(packaging).containsExactly(new ItemPackaging(productId, 2, 180, 22, 12, 6));
        inventory.verify(1, getRequestedFor(urlPathEqualTo(path()))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Role", absent()));
    }

    @Test
    void inventoryIsCalledOncePerDistinctProduct() {
        stub(okJson(packagingJson()));

        provider(inventory.baseUrl()).packagingFor(List.of(item(1), item(3)));

        inventory.verify(1, getRequestedFor(urlPathEqualTo(path())));
    }

    @Test
    void notFound_isNotRetryable_stockItemNotFound() {
        stub(aResponse().withStatus(404));

        assertFailure("stock_item_not_found:" + productId, false);
    }

    @Test
    void unprocessable_isNotRetryable_packagingDataMissing() {
        stub(aResponse().withStatus(422).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"reason\":\"packaging_data_missing\"}"));

        assertFailure("packaging_data_missing:" + productId, false);
    }

    @Test
    void serverError_isRetryable() {
        stub(aResponse().withStatus(503));

        assertFailure("inventory_unavailable", true);
    }

    @Test
    void unexpectedClientError_isRetryable() {
        stub(aResponse().withStatus(403));

        assertFailure("inventory_unavailable", true);
    }

    @Test
    void brokenConnection_isRetryable() {
        stub(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        assertFailure("inventory_unavailable", true);
    }

    @Test
    void timeout_isRetryable() {
        stub(okJson(packagingJson()).withFixedDelay((int) READ_TIMEOUT.toMillis() + 1000));

        assertFailure("inventory_unavailable", true);
    }

    @Test
    void inventoryDown_isRetryable() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> provider("http://localhost:" + freePort).packagingFor(List.of(item(1))))
                .isInstanceOfSatisfying(PackagingUnavailableException.class, e -> {
                    assertThat(e.getReason()).isEqualTo("inventory_unavailable");
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    private void assertFailure(String reason, boolean retryable) {
        assertThatThrownBy(() -> provider(inventory.baseUrl()).packagingFor(List.of(item(1))))
                .isInstanceOfSatisfying(PackagingUnavailableException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(reason);
                    assertThat(e.isRetryable()).isEqualTo(retryable);
                    assertThat(e.getMessage()).doesNotContain(TOKEN);
                });
    }

    private void stub(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        inventory.stubFor(get(urlPathEqualTo(path())).willReturn(response));
    }

    private String path() {
        return "/inventory/stock/" + productId + "/packaging";
    }

    private ShipmentItem item(int quantity) {
        return new ShipmentItem(productId, quantity);
    }

    private static String packagingJson() {
        return "{\"grossWeightGrams\":100,\"lengthCm\":10,\"widthCm\":10,\"heightCm\":10}";
    }

    private static InventoryPackagingProvider provider(String baseUrl) {
        InventoryClientProperties properties = new InventoryClientProperties(baseUrl, Duration.ofSeconds(1),
                READ_TIMEOUT);
        return new InventoryPackagingProvider(ClientsConfiguration.createInventoryClient(properties, TOKEN));
    }
}
