package ua.edu.ukma.springers.voltstore.inventory.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.support.Concurrently;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 19, end to end: adjust and set-packaging for a product without a StockItem ask a fake Catalog (WireMock)
 * whether the product exists, before anything is created.
 */
@SpringBootTest(properties = {"clients.catalog.read-timeout=1s", "clients.catalog.retry-delay=50ms"})
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class CatalogExistenceCheckIntegrationTest {
    private static final String BATCH_GET = "/catalog/products/batch-get";
    private static final String PACKAGING = "{\"grossWeightGrams\":500,\"lengthCm\":10,\"widthCm\":5,\"heightCm\":2}";

    @RegisterExtension
    static WireMockExtension catalog = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void catalogUrl(DynamicPropertyRegistry registry) {
        registry.add("clients.catalog.url", catalog::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM stock_item_reservations");
        jdbc.update("DELETE FROM stock_items");
    }

    @Test
    void adjust_productKnownToCatalog_createsTheStockItem() throws Exception {
        UUID productId = UUID.randomUUID();
        catalogKnows(productId);

        adjust(productId, 10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAvailableQuantity").value(10));

        assertThat(availableQuantity(productId)).containsExactly(10);
        catalog.verify(1, getRequestedFor(urlPathEqualTo(BATCH_GET))
                .withQueryParam("ids", equalTo(productId.toString()))
                .withHeader("X-Internal-Token", equalTo(TrustedHeaders.INTERNAL_TOKEN)));
    }

    @Test
    void adjust_productKnownToCatalog_storesTheCopyFromTheAnswer() throws Exception {
        UUID productId = UUID.randomUUID();
        catalogKnows(productId);

        adjust(productId, 10).andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT title, product_status, product_version FROM stock_items "
                + "WHERE product_id = ?", productId))
                .containsEntry("title", "Laptop")
                .containsEntry("product_status", "NOT_ACTIVE")
                .containsEntry("product_version", 3L);
    }

    @Test
    void adjust_answerWithoutVersion_createsTheRowWithoutACopy() throws Exception {
        UUID productId = UUID.randomUUID();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson("""
                [{"productId":"%s","title":"Laptop","basePrice":999.99,"status":"NOT_ACTIVE"}]
                """.formatted(productId))));

        adjust(productId, 10).andExpect(status().isOk());

        assertThat(availableQuantity(productId)).containsExactly(10);
        assertThat(jdbc.queryForObject("SELECT title FROM stock_items WHERE product_id = ?", String.class,
                productId)).isNull();
    }

    @Test
    void adjust_productUnknownToCatalog_returns404AndCreatesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson("[]")));

        adjust(productId, 10)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andExpect(jsonPath("$.detail").value("Product " + productId + " not found in Catalog"));

        assertThat(availableQuantity(productId)).isEmpty();
    }

    @Test
    void adjust_catalogFails_returns503WithAFixedMessageAndCreatesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(aResponse().withStatus(500).withBody("db down")));

        adjust(productId, 10)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/service-unavailable"))
                .andExpect(jsonPath("$.detail")
                        .value("The product cannot be checked in the catalog right now. Please try again later."));

        assertThat(availableQuantity(productId)).isEmpty();
        // Task 20: a 5xx is retried once, then answered with 503
        catalog.verify(2, getRequestedFor(urlPathEqualTo(BATCH_GET)));
    }

    @Test
    void adjust_existingStockItem_neverCallsCatalog() throws Exception {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_items (product_id, available_quantity) VALUES (?, 5)", productId);

        adjust(productId, 3).andExpect(status().isOk());
        putPackaging(productId).andExpect(status().isNoContent());

        assertThat(availableQuantity(productId)).containsExactly(8);
        catalog.verify(0, getRequestedFor(urlPathEqualTo(BATCH_GET)));
    }

    @Test
    void setPackaging_productKnownToCatalog_returns204AndCreatesTheStockItem() throws Exception {
        UUID productId = UUID.randomUUID();
        catalogKnows(productId);

        putPackaging(productId).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT gross_weight_grams FROM stock_items WHERE product_id = ?",
                Integer.class, productId)).isEqualTo(500);
    }

    @Test
    void setPackaging_productUnknownToCatalog_returns404AndCreatesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(okJson("[]")));

        putPackaging(productId).andExpect(status().isNotFound());

        assertThat(availableQuantity(productId)).isEmpty();
    }

    @Test
    void setPackaging_catalogRejectsTheToken_returns503AndCreatesNothing() throws Exception {
        UUID productId = UUID.randomUUID();
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).willReturn(aResponse().withStatus(401)));

        putPackaging(productId).andExpect(status().isServiceUnavailable());

        assertThat(availableQuantity(productId)).isEmpty();
        // A configuration problem is not retried
        catalog.verify(1, getRequestedFor(urlPathEqualTo(BATCH_GET)));
    }

    @Test
    void concurrentFirstTimeAdjusts_createOneStockItemWithBothDeltas() throws Exception {
        UUID productId = UUID.randomUUID();
        catalogKnows(productId);

        List<Object> results = Concurrently.run(
                () -> adjust(productId, 4).andReturn().getResponse().getStatus(),
                () -> adjust(productId, 6).andReturn().getResponse().getStatus());

        assertThat(results).containsOnly(200);
        assertThat(availableQuantity(productId)).containsExactly(10);
    }

    private void catalogKnows(UUID productId) {
        catalog.stubFor(get(urlPathEqualTo(BATCH_GET)).withQueryParam("ids", equalTo(productId.toString()))
                .willReturn(okJson("""
                        [{"productId":"%s","title":"Laptop","basePrice":999.99,"status":"NOT_ACTIVE","version":3}]
                        """.formatted(productId))));
    }

    private ResultActions adjust(UUID productId, int delta) throws Exception {
        return mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + "}"));
    }

    private ResultActions putPackaging(UUID productId) throws Exception {
        return mockMvc.perform(put("/inventory/stock/" + productId + "/packaging")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                .contentType(MediaType.APPLICATION_JSON).content(PACKAGING));
    }

    private List<Integer> availableQuantity(UUID productId) {
        return jdbc.queryForList("SELECT available_quantity FROM stock_items WHERE product_id = ?", Integer.class,
                productId);
    }
}
