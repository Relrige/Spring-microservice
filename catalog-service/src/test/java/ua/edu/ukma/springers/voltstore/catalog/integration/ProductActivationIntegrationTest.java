package ua.edu.ukma.springers.voltstore.catalog.integration;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertCategory;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertProduct;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asManager;

// Tasks 21 and 22 end to end: PATCH .../status with ACTIVE asks Inventory (WireMock) before activating
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductActivationIntegrationTest {
    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void inventoryClient(DynamicPropertyRegistry registry) {
        registry.add("clients.inventory.url", inventory::baseUrl);
        registry.add("clients.inventory.read-timeout", () -> "500ms");
        registry.add("clients.inventory.retry-delay", () -> "10ms");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID productId;

    @BeforeEach
    void setUp() {
        clean(jdbc);
        productId = insertProduct(jdbc, insertCategory(jdbc, "Laptops"), ProductStatus.NOT_ACTIVE, StockStatus.OUT_OF_STOCK);
    }

    @Test
    void ready_activatesTheProduct() throws Exception {
        stubReadiness(aResponse().withStatus(204));

        activate().andExpect(status().isOk());

        assertThat(storedStatus()).isEqualTo("ACTIVE");
        // Authenticated as a service, never on behalf of the manager
        inventory.verify(getRequestedFor(urlEqualTo(readinessPath()))
                .withHeader("X-Internal-Token", equalTo(TrustedHeaders.INTERNAL_TOKEN))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Role", absent()));
    }

    @Test
    void packagingMissing_returns422WithReasonAndKeepsStatus() throws Exception {
        stubReadiness(aResponse().withStatus(422).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":422,\"reason\":\"packaging_data_missing\"}"));

        activate()
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("packaging_data_missing"));

        assertThat(storedStatus()).isEqualTo("NOT_ACTIVE");
    }

    @Test
    void noStockItem_returns422StockItemNotFound() throws Exception {
        stubReadiness(aResponse().withStatus(404));

        activate()
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("stock_item_not_found"));

        assertThat(storedStatus()).isEqualTo("NOT_ACTIVE");
    }

    @Test
    void inventoryDown_returns503AndKeepsStatus() throws Exception {
        stubReadiness(aResponse().withStatus(500));

        activate()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"));

        assertThat(storedStatus()).isEqualTo("NOT_ACTIVE");
        // One retry, then give up
        inventory.verify(2, getRequestedFor(urlEqualTo(readinessPath())));
    }

    @Test
    void inventorySlow_returns503() throws Exception {
        stubReadiness(aResponse().withStatus(204).withFixedDelay(2000));

        activate().andExpect(status().isServiceUnavailable());

        assertThat(storedStatus()).isEqualTo("NOT_ACTIVE");
    }

    @Test
    void deactivation_neverCallsInventory() throws Exception {
        mockMvc.perform(patch("/catalog/products/" + productId + "/status").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NOT_ACTIVE\"}"))
                .andExpect(status().isOk());

        inventory.verify(0, getRequestedFor(urlEqualTo(readinessPath())));
    }

    private ResultActions activate() throws Exception {
        return mockMvc.perform(patch("/catalog/products/" + productId + "/status").headers(asManager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"));
    }

    private void stubReadiness(ResponseDefinitionBuilder response) {
        inventory.stubFor(get(readinessPath()).willReturn(response));
    }

    private String readinessPath() {
        return "/inventory/stock/" + productId + "/readiness";
    }

    private String storedStatus() {
        return jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, productId);
    }
}
