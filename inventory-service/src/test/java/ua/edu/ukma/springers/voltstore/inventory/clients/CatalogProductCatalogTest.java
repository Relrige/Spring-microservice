package ua.edu.ukma.springers.voltstore.inventory.clients;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException;
import ua.edu.ukma.springers.voltstore.inventory.services.CatalogProductData;
import ua.edu.ukma.springers.voltstore.inventory.filters.CorrelationIdKeys;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tasks 19 and 20. The adapter against a fake Catalog (WireMock), without a Spring context: the client is built
 * exactly as in production, with a short read timeout and a short retry delay so the tests stay fast.
 */
class CatalogProductCatalogTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";
    private static final String PATH = "/catalog/products/batch-get";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    @RegisterExtension
    static WireMockExtension catalog = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final UUID productId = UUID.randomUUID();

    // The very first call loads the HTTP client and JSON classes, which can take longer than the short read
    // timeout; do it once here so no test fails for a cold JVM
    @BeforeAll
    static void warmUp() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]")));
        CatalogClientProperties relaxed = new CatalogClientProperties(catalog.baseUrl(), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 0, Duration.ZERO);
        new CatalogProductCatalog(ClientsConfiguration.createCatalogClient(relaxed, TOKEN), relaxed)
                .find(UUID.randomUUID());
        catalog.resetAll();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void productInTheAnswer_isFoundWithItsData_andTheRequestCarriesTheIdAndTheInternalTokenOnly() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"productId":"%s","title":"Laptop","basePrice":999.99,"status":"NOT_ACTIVE","version":3,
                  "futureField":1}]
                """.formatted(productId))));

        assertThat(adapter(catalog.baseUrl()).find(productId))
                .contains(new CatalogProductData(productId, "Laptop", "NOT_ACTIVE", 3L));

        catalog.verify(1, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("ids", equalTo(productId.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Role", absent()));
    }

    @Test
    void answerFromAnOlderCatalog_withoutVersion_isFoundWithoutIt_andAnUnknownStatusIsKeptAsText() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"productId":"%s","title":"Laptop","basePrice":1,"status":"ARCHIVED"}]
                """.formatted(productId))));

        assertThat(adapter(catalog.baseUrl()).find(productId))
                .contains(new CatalogProductData(productId, "Laptop", "ARCHIVED", null));
    }

    @Test
    void findAll_sendsAllIdsInOneRequest_andReturnsTheKnownProducts() {
        UUID other = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"productId":"%s","title":"A","status":"ACTIVE","version":1},
                 {"productId":"%s","title":"B","status":"NOT_ACTIVE","version":2}]
                """.formatted(productId, other))));

        assertThat(adapter(catalog.baseUrl()).findAll(List.of(productId, other, unknown)))
                .extracting(CatalogProductData::productId).containsExactlyInAnyOrder(productId, other);
        catalog.verify(1, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("ids", havingExactly(productId.toString(), other.toString(), unknown.toString())));
    }

    @Test
    void emptyAnswer_doesNotExist_andIsNotRetried() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]")));

        assertThat(adapter(catalog.baseUrl()).find(productId)).isEmpty();
        catalog.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void answerWithOtherProductsOnly_doesNotExist() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                [{"productId":"%s","title":"Other","basePrice":1,"status":"ACTIVE"}]
                """.formatted(UUID.randomUUID()))));

        assertThat(adapter(catalog.baseUrl()).find(productId)).isEmpty();
    }

    @Test
    void correlationId_isForwarded() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]")));
        MDC.put(CorrelationIdKeys.CORRELATION_ID_MDC_KEY, "corr-42");

        adapter(catalog.baseUrl()).find(productId);

        catalog.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader(CorrelationIdKeys.CORRELATION_ID_HTTP_HEADER, equalTo("corr-42")));
    }

    @Test
    void down_serverErrorTwice_isUnavailableAfterOneRetry() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter(catalog.baseUrl()).find(productId))
                .isInstanceOf(CatalogUnavailableException.class);
        catalog.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void slow_readTimeoutTwice_isUnavailableAfterOneRetry() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]")
                .withFixedDelay((int) READ_TIMEOUT.multipliedBy(2).toMillis())));

        assertThatThrownBy(() -> adapter(catalog.baseUrl()).find(productId))
                .isInstanceOf(CatalogUnavailableException.class);
        catalog.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void flapping_unavailableThenFound_existsAfterTheRetry() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).inScenario("flapping").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        catalog.stubFor(get(urlPathEqualTo(PATH)).inScenario("flapping").whenScenarioStateIs("recovered")
                .willReturn(okJson("""
                        [{"productId":"%s","title":"Laptop","basePrice":1,"status":"NOT_ACTIVE"}]
                        """.formatted(productId))));

        assertThat(adapter(catalog.baseUrl()).find(productId)).isPresent();
        catalog.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void brokenConnection_isUnavailable() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter(catalog.baseUrl()).find(productId))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void connectionRefused_isUnavailable() throws Exception {
        assertThatThrownBy(() -> adapter("http://localhost:" + freePort()).find(productId))
                .isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void unauthorized_isAConfigurationProblem_reportedAsUnavailable() {
        catalog.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> adapter(catalog.baseUrl()).find(productId))
                .isInstanceOf(CatalogUnavailableException.class)
                .hasMessageNotContaining(TOKEN);
        // A configuration problem: retrying cannot fix it
        catalog.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    private static CatalogProductCatalog adapter(String baseUrl) {
        CatalogClientProperties properties = new CatalogClientProperties(baseUrl, Duration.ofSeconds(1), READ_TIMEOUT,
                1, Duration.ofMillis(50));
        return new CatalogProductCatalog(ClientsConfiguration.createCatalogClient(properties, TOKEN), properties);
    }

    // A port nobody listens on: bound and released at once
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
