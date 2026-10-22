package ua.edu.ukma.springers.voltstore.inventory.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductEvent;
import ua.edu.ukma.springers.voltstore.inventory.services.ProductDataBackfill;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 23 (EP-INV-JOB-01) against a fake Catalog (WireMock). The job method is called directly; the schedule is
 * pushed out of the way in the test properties.
 */
@SpringBootTest(properties = {"clients.catalog.read-timeout=5s", "clients.catalog.retry-delay=50ms"})
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class ProductDataBackfillIntegrationTest {
    private static final String BATCH_GET = "/catalog/products/batch-get";

    @RegisterExtension
    static WireMockExtension catalog = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) {
        registry.add("clients.catalog.url", catalog::baseUrl);
    }

    @Autowired
    private ProductDataBackfill backfill;

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM stock_item_reservations");
        jdbc.update("DELETE FROM stock_items");
    }

    @Test
    void rowsWithoutTitle_areFilledInBatchesOf100_andRowsWithATitleAreNeverRequested() {
        // PostgreSQL orders UUIDs like their text form (Java's UUID.compareTo does not)
        List<UUID> missing = IntStream.range(0, 150).mapToObj(i -> rowWithoutCopy())
                .sorted(Comparator.comparing(UUID::toString)).toList();
        UUID alreadyCopied = UUID.randomUUID();
        stockService.applyProductUpdated(new ProductEvent(UUID.randomUUID(), alreadyCopied, "Copied", "ACTIVE", 7L,
                null));
        catalogAnswers(missing.subList(0, 100), 1);
        catalogAnswers(missing.subList(100, 150), 1);

        assertThat(backfill.fillMissingProductData()).isEqualTo(150);

        catalog.verify(2, getRequestedFor(urlPathEqualTo(BATCH_GET)));
        assertThat(count("SELECT count(*) FROM stock_items WHERE title IS NULL")).isZero();
        assertThat(copyOf(missing.getFirst())).containsExactly("Product " + missing.getFirst(), "ACTIVE", 1L);
        assertThat(copyOf(alreadyCopied)).containsExactly("Copied", "ACTIVE", 7L);
        // Quantities are untouched
        assertThat(count("SELECT count(*) FROM stock_items WHERE available_quantity = 3")).isEqualTo(150);
    }

    @Test
    void newerEventAppliedDuringTheCatalogCall_wins() throws Exception {
        UUID productId = rowWithoutCopy();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson(answer(productId, "Old title", 3))
                .withFixedDelay(1500)));

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(backfill::fillMissingProductData);
        await().atMost(Duration.ofSeconds(5)).until(() -> !catalog.getAllServeEvents().isEmpty());
        stockService.applyProductUpdated(new ProductEvent(UUID.randomUUID(), productId, "New title", "ACTIVE", 5L,
                null));

        assertThat(run.get()).isZero();
        assertThat(copyOf(productId)).containsExactly("New title", "ACTIVE", 5L);
    }

    @Test
    void catalogUnavailable_changesNothing_andTheJobDoesNotThrow() {
        UUID productId = rowWithoutCopy();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(aResponse().withStatus(503)));

        assertThat(backfill.fillMissingProductData()).isZero();

        assertThat(copyOf(productId)).containsOnlyNulls();
        // The client's one retry, then the run ends without trying further batches
        catalog.verify(2, getRequestedFor(urlPathEqualTo(BATCH_GET)));
    }

    @Test
    void productUnknownToCatalog_staysNull_andTheOthersAreFilled() {
        UUID known = rowWithoutCopy();
        UUID unknown = rowWithoutCopy();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson(answer(known, "Known", 2))));

        assertThat(backfill.fillMissingProductData()).isEqualTo(1);

        assertThat(copyOf(known)).containsExactly("Known", "ACTIVE", 2L);
        assertThat(copyOf(unknown)).containsOnlyNulls();
    }

    @Test
    void noRowWithoutTitle_makesNoCatalogCall() {
        stockService.applyProductUpdated(new ProductEvent(UUID.randomUUID(), UUID.randomUUID(), "Copied", "ACTIVE",
                1L, null));

        assertThat(backfill.fillMissingProductData()).isZero();

        catalog.verify(0, getRequestedFor(urlPathEqualTo(BATCH_GET)));
    }

    @Test
    void answerWithoutVersion_isSkipped() {
        UUID productId = rowWithoutCopy();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson("""
                [{"productId":"%s","title":"Laptop","basePrice":1,"status":"ACTIVE"}]
                """.formatted(productId))));

        assertThat(backfill.fillMissingProductData()).isZero();

        assertThat(copyOf(productId)).containsOnlyNulls();
    }

    @Test
    void oneRun_readsAtMostTenBatches_andMovesPastUnknownProducts() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            rows.add(new Object[]{UUID.randomUUID()});
        }
        jdbc.batchUpdate("INSERT INTO stock_items (product_id) VALUES (?)", rows);
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson("[]")));

        assertThat(backfill.fillMissingProductData()).isZero();

        // Ten different batches of 100: the unknown products of one batch are not requested again in the next
        catalog.verify(10, getRequestedFor(urlPathEqualTo(BATCH_GET)));
        List<String> requested = catalog.getAllServeEvents().stream()
                .flatMap(event -> event.getRequest().queryParameter("ids").values().stream()).toList();
        assertThat(requested).hasSize(1000).doesNotHaveDuplicates();
    }

    private UUID rowWithoutCopy() {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_items (product_id, available_quantity) VALUES (?, 3)", productId);
        return productId;
    }

    private void catalogAnswers(List<UUID> ids, long version) {
        String body = ids.stream().map(id -> """
                {"productId":"%s","title":"Product %s","basePrice":1,"status":"ACTIVE","version":%d}"""
                .formatted(id, id, version)).collect(Collectors.joining(",", "[", "]"));
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET))
                .withQueryParam("ids", havingExactly(ids.stream().map(UUID::toString).toArray(String[]::new)))
                .willReturn(okJson(body)));
    }

    private static String answer(UUID productId, String title, long version) {
        return """
                [{"productId":"%s","title":"%s","basePrice":1,"status":"ACTIVE","version":%d}]
                """.formatted(productId, title, version);
    }

    private List<Object> copyOf(UUID productId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, product_status, product_version FROM stock_items WHERE product_id = ?", productId);
        return new ArrayList<>(row.values());
    }

    private Integer count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
