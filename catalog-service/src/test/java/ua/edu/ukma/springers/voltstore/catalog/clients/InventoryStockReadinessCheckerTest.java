package ua.edu.ukma.springers.voltstore.catalog.clients;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.InventoryUnavailableException;
import ua.edu.ukma.springers.voltstore.catalog.services.StockReadiness;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter against a fake Inventory (WireMock), without a Spring context: the client is built exactly as in
 * production, with short timeouts and one retry so the tests stay fast.
 */
class InventoryStockReadinessCheckerTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID productId = UUID.randomUUID();
    private final String path = "/inventory/stock/" + productId + "/readiness";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // --- business answers (task 21) ---

    @Test
    void noContent_isReady() {
        inventory.stubFor(get(path).willReturn(aResponse().withStatus(204)));

        assertThat(checker(inventory.baseUrl()).check(productId)).isEqualTo(StockReadiness.isReady());
    }

    @Test
    void unprocessable_isNotReadyWithInventorysReason_andIsNotRetried() {
        inventory.stubFor(get(path).willReturn(problem(422,
                "{\"type\":\"x\",\"status\":422,\"reason\":\"packaging_data_missing\",\"futureField\":1}")));

        assertThat(checker(inventory.baseUrl()).check(productId))
                .isEqualTo(StockReadiness.notReady("packaging_data_missing"));
        inventory.verify(1, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    void unprocessable_unknownReasonIsPassedThrough() {
        inventory.stubFor(get(path).willReturn(problem(422, "{\"reason\":\"warehouse_closed\"}")));

        assertThat(checker(inventory.baseUrl()).check(productId).reason()).isEqualTo("warehouse_closed");
    }

    @Test
    void unprocessable_withoutReason_isStillNotReady() {
        inventory.stubFor(get(path).willReturn(aResponse().withStatus(422).withBody("not json")));

        assertThat(checker(inventory.baseUrl()).check(productId))
                .isEqualTo(StockReadiness.notReady(InventoryStockReadinessChecker.UNKNOWN_REASON));
    }

    @Test
    void notFound_isNotReadyStockItemNotFound_andIsNotRetried() {
        inventory.stubFor(get(path).willReturn(problem(404, "{\"status\":404}")));

        assertThat(checker(inventory.baseUrl()).check(productId))
                .isEqualTo(StockReadiness.notReady("stock_item_not_found"));
        inventory.verify(1, getRequestedFor(urlEqualTo(path)));
    }

    // --- Inventory unavailable (tasks 21 and 22) ---

    @Test
    void serverError_isRetriedOnceThenUnavailable() {
        inventory.stubFor(get(path).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> checker(inventory.baseUrl()).check(productId))
                .isInstanceOf(InventoryUnavailableException.class);
        inventory.verify(2, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    void slowResponse_timesOutAndIsUnavailable() {
        inventory.stubFor(get(path).willReturn(aResponse().withStatus(204)
                .withFixedDelay((int) READ_TIMEOUT.toMillis() * 3)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> checker(inventory.baseUrl()).check(productId))
                .isInstanceOf(InventoryUnavailableException.class);
        // Two attempts bounded by the read timeout, not by the slow response
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(READ_TIMEOUT.multipliedBy(5));
        inventory.verify(2, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    void connectionRefused_isUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> checker("http://localhost:" + closedPort).check(productId))
                .isInstanceOf(InventoryUnavailableException.class);
    }

    @Test
    void flapping_failureThenSuccess_isReadyAfterTheRetry() {
        inventory.stubFor(get(path).inScenario("flapping").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        inventory.stubFor(get(path).inScenario("flapping").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(204)));

        assertThat(checker(inventory.baseUrl()).check(productId).ready()).isTrue();
        inventory.verify(2, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    void forbidden_isAConfigurationProblem_unavailableWithoutRetry() {
        inventory.stubFor(get(path).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> checker(inventory.baseUrl()).check(productId))
                .isInstanceOf(InventoryUnavailableException.class);
        inventory.verify(1, getRequestedFor(urlEqualTo(path)));
    }

    // --- headers ---

    @Test
    void sendsInternalTokenAndCorrelationId_butNoUserHeaders() {
        inventory.stubFor(get(path).willReturn(aResponse().withStatus(204)));
        MDC.put("correlationId", "corr-123");

        checker(inventory.baseUrl()).check(productId);

        inventory.verify(getRequestedFor(urlEqualTo(path))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withHeader("X-Correlation-Id", equalTo("corr-123"))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Role", absent()));
    }

    private static InventoryStockReadinessChecker checker(String baseUrl) {
        InventoryClientProperties properties = new InventoryClientProperties(baseUrl, Duration.ofMillis(500),
                READ_TIMEOUT, 1, Duration.ofMillis(10));
        return new InventoryStockReadinessChecker(ClientsConfiguration.createInventoryClient(properties, TOKEN),
                properties);
    }

    private static ResponseDefinitionBuilder problem(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json").withBody(body);
    }
}
