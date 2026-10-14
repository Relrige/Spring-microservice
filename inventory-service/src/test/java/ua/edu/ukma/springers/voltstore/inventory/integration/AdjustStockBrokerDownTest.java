package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.util.UUID;

import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The broker is unreachable, so the 0 -> positive event cannot be delivered. The adjustment must
 * still succeed: the publish failure is only logged (the gap that task 17, the outbox, closes).
 * <p>
 * Timeouts are shortened as in {@link KafkaBrokerDownStartupTest}. In production {@code send()} blocks for up to
 * {@code max.block.ms} (60 s by default) waiting for broker metadata before the failure is logged.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        // Topic creation by KafkaAdmin at startup: give up after 1 s instead of 30 s (+ 10 s to close the client)
        "spring.kafka.admin.operation-timeout=1s",
        "spring.kafka.admin.close-timeout=1s",
        // KafkaTemplate.send() waits for broker metadata: 1 s instead of 60 s
        "spring.kafka.producer.properties.max.block.ms=1000"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
class AdjustStockBrokerDownTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StockItemRepository repository;

    @Test
    void adjust_brokerDown_stillReturns200AndPersistsStock() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newAvailableQuantity").value(10));

        assertThat(repository.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(10);
    }
}
