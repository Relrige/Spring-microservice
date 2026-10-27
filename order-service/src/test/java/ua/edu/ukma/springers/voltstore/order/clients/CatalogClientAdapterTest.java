package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.bulkhead.Bulkhead;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogClientAdapterTest extends ClientsTestBase {
    private static final String PATH = "/catalog/products/batch-get";
    private static final UUID PHONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CASE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private ProductCatalog catalog;

    @Test
    void sendsTheToken_andMapsCatalogsContract() {
        stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                [ {"productId":"%s","title":"Phone","basePrice":19999.50,"status":"ACTIVE","stockStatus":"IN_STOCK",
                   "unknownField":"ignored"},
                  {"productId":"%s","title":"Case","basePrice":499.00,"status":"NOT_ACTIVE"} ]
                """.formatted(PHONE, CASE))));

        List<CatalogProduct> products = catalog.findProducts(List.of(PHONE, CASE, PHONE));

        assertThat(products).extracting(CatalogProduct::productId).containsExactly(PHONE, CASE);
        assertThat(products.getFirst().isActive()).isTrue();
        assertThat(products.getFirst().stockStatus()).isEqualTo(CatalogProduct.StockStatus.IN_STOCK);
        assertThat(products.get(1).stockStatus()).as("not sent until Catalog task 24").isNull();
        verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("ids", havingExactly(PHONE.toString(), CASE.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withoutHeader("X-User-Id"));
    }

    @Test
    void noIds_noCall() {
        assertThat(catalog.findProducts(List.of())).isEmpty();
        verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void moreThan100Ids_isAProgrammingError_notAnOutage() {
        List<UUID> ids = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> catalog.findProducts(ids)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a503_isRetried_andTheSecondAnswerIsUsed() {
        stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("up"));
        stubFor(get(urlPathEqualTo(PATH)).inScenario("flaky").whenScenarioStateIs("up").willReturn(okJson("[]")));

        assertThat(catalog.findProducts(List.of(PHONE))).isEmpty();
        verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void aPersistent5xx_endsAsDependencyUnavailable_afterThreeAttempts() {
        stubFor(get(urlPathEqualTo(PATH)).willReturn(serverError()));

        assertThatThrownBy(() -> catalog.findProducts(List.of(PHONE)))
                .isInstanceOfSatisfying(DependencyUnavailableException.class,
                        e -> assertThat(e.getService()).isEqualTo("Catalog"));
        verify(3, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void aSlowCatalog_hitsTheReadTimeout() {
        stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("[]").withFixedDelay(1500)));

        assertThatThrownBy(() -> catalog.findProducts(List.of(PHONE)))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void a4xx_isNotRetried() {
        stubFor(get(urlPathEqualTo(PATH)).willReturn(unauthorized()));

        assertThatThrownBy(() -> catalog.findProducts(List.of(PHONE)))
                .isInstanceOf(DependencyUnavailableException.class);
        verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void afterRepeatedFailures_theCircuitOpens_andCatalogIsNoLongerCalled() {
        stubFor(get(urlPathEqualTo(PATH)).willReturn(serverError()));
        // 2 calls x 3 attempts = 6 recorded failures, more than the 4 the breaker needs to open
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> catalog.findProducts(List.of(PHONE)))
                    .isInstanceOf(DependencyUnavailableException.class);
        }
        int callsSoFar = wireMock.getAllServeEvents().size();

        assertThatThrownBy(() -> catalog.findProducts(List.of(PHONE)))
                .isInstanceOf(DependencyUnavailableException.class);

        assertThat(wireMock.getAllServeEvents()).hasSize(callsSoFar);
    }

    @Test
    void aFullBulkhead_failsFastAsDependencyUnavailable() {
        Bulkhead bulkhead = bulkheads().bulkhead(CatalogClientAdapter.NAME);
        int permits = bulkhead.getBulkheadConfig().getMaxConcurrentCalls();
        for (int i = 0; i < permits; i++) {
            bulkhead.acquirePermission();
        }
        try {
            assertThatThrownBy(() -> catalog.findProducts(List.of(PHONE)))
                    .isInstanceOf(DependencyUnavailableException.class);
            verify(0, getRequestedFor(urlPathEqualTo(PATH)));
        } finally {
            for (int i = 0; i < permits; i++) {
                bulkhead.onComplete();
            }
        }
    }
}
