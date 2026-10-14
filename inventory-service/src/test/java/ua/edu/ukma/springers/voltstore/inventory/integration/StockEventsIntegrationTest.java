package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.support.CatalogKnowsEveryProduct;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, CatalogKnowsEveryProduct.class, StockEventsIntegrationTest.Collector.class})
class StockEventsIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Collector {
        private final List<ConsumerRecord<String, String>> records = new CopyOnWriteArrayList<>();

        @Bean
        List<ConsumerRecord<String, String>> collectedRecords() {
            return records;
        }

        @KafkaListener(topics = KafkaTopics.STOCK_EVENTS, groupId = "stock-events-test")
        void collect(ConsumerRecord<String, String> record) {
            records.add(record);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<ConsumerRecord<String, String>> collectedRecords;

    @Test
    void restockFromZero_publishesOneReplenishedWithProductIdAsKey() throws Exception {
        UUID productId = UUID.randomUUID();

        adjust(productId, 10).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(typesFor(productId)).containsExactly("ProductStockReplenished"));
        ConsumerRecord<String, String> record = recordsFor(productId).getFirst();
        assertThat(record.key()).isEqualTo(productId.toString());
        assertThat(record.partition()).isBetween(0, 2);
        assertThat(record.offset()).isNotNegative();
        assertThat(record.value()).contains("\"productId\":\"" + productId + "\"").contains("\"eventId\"");
        assertThat(Instant.parse(jsonString(record.value(), "timestamp"))).isNotNull();
    }

    @Test
    void replenishThenDeplete_publishesBothInOrder_andNoEventForPositiveToPositiveOrRejectedWriteOff() throws Exception {
        UUID productId = UUID.randomUUID();

        adjust(productId, 5).andExpect(status().isOk());   // 0 -> 5: Replenished
        adjust(productId, 3).andExpect(status().isOk());   // 5 -> 8: nothing
        adjust(productId, -9).andExpect(status().isUnprocessableContent());   // rejected: nothing
        adjust(productId, -8).andExpect(status().isOk());  // 8 -> 0: Depleted

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(typesFor(productId)).containsExactly("ProductStockReplenished", "ProductStockDepleted"));
        List<ConsumerRecord<String, String>> records = recordsFor(productId);
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
        assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
    }

    @Test
    void reserveLastUnits_publishesDepletedOnlyForProductThatReachesZero() throws Exception {
        UUID depleted = UUID.randomUUID();
        UUID stillAvailable = UUID.randomUUID();
        adjust(depleted, 2).andExpect(status().isOk());
        adjust(stillAvailable, 5).andExpect(status().isOk());

        mockMvc.perform(post("/inventory/reservations")
                        .headers(TrustedHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\",\"items\":["
                                + "{\"productId\":\"" + depleted + "\",\"quantity\":2},"
                                + "{\"productId\":\"" + stillAvailable + "\",\"quantity\":1}]}"))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(typesFor(depleted)).containsExactly("ProductStockReplenished", "ProductStockDepleted"));
        // 5 -> 4 crosses no boundary: only the initial restock event (checked after Depleted has arrived)
        assertThat(typesFor(stillAvailable)).containsExactly("ProductStockReplenished");
    }

    @Test
    void releaseFromZero_publishesReplenished() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        adjust(productId, 3).andExpect(status().isOk());   // 0 -> 3: Replenished

        mockMvc.perform(post("/inventory/reservations")
                        .headers(TrustedHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"items\":["
                                + "{\"productId\":\"" + productId + "\",\"quantity\":3}]}"))
                .andExpect(status().isNoContent());   // 3 -> 0: Depleted
        mockMvc.perform(post("/inventory/reservations/" + orderId + "/release")
                        .headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isNoContent());   // 0 -> 3: Replenished
        mockMvc.perform(post("/inventory/reservations/" + orderId + "/release")
                        .headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isNoContent());   // nothing ACTIVE: no event

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(typesFor(productId)).containsExactly(
                        "ProductStockReplenished", "ProductStockDepleted", "ProductStockReplenished"));
    }

    private org.springframework.test.web.servlet.ResultActions adjust(UUID productId, int delta) throws Exception {
        return mockMvc.perform(post("/inventory/stock/" + productId + "/adjust")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":" + delta + "}"));
    }

    private List<ConsumerRecord<String, String>> recordsFor(UUID productId) {
        return collectedRecords.stream().filter(r -> productId.toString().equals(r.key())).toList();
    }

    private List<String> typesFor(UUID productId) {
        return recordsFor(productId).stream()
                .map(r -> new String(r.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .toList();
    }

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("field %s in %s", field, json).isTrue();
        return matcher.group(1);
    }
}
