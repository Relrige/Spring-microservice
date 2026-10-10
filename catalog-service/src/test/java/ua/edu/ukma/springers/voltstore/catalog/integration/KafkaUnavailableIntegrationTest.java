package ua.edu.ukma.springers.voltstore.catalog.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.catalog.PostgresContainerConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertCategory;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.productJson;

// The broker is unreachable for the whole test: nothing listens on localhost:1
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.operation-timeout=2s"
})
@AutoConfigureMockMvc
@Import(PostgresContainerConfiguration.class)
class KafkaUnavailableIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void applicationStartsAndReadinessDoesNotDependOnKafka() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void createProduct_stillReturns201WithoutWaitingForTheBroker() throws Exception {
        clean(jdbc);
        UUID categoryId = insertCategory(jdbc, "Laptops");

        long start = System.nanoTime();
        mockMvc.perform(post("/catalog/products").contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(categoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").isNotEmpty());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // Publishing runs asynchronously; the request must not block for max.block.ms (5 s)
        assertThat(elapsedMillis).isLessThan(3000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(1);
    }
}
