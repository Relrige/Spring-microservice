package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents the behaviour when the broker is unreachable at start: no Kafka container is started and
 * the bootstrap address points at a closed port. The service must still start, and readiness must not
 * depend on Kafka (Spring Boot has no Kafka health indicator).
 * <p>
 * The Kafka timeouts are shortened for the test only (production: 30 s topic creation, 60 s blocking send)
 * so the context starts in seconds. The properties are identical in {@link AdjustStockBrokerDownTest}, so both
 * classes share one cached context.
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
class KafkaBrokerDownStartupTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void brokerDown_applicationStartsAndReadinessIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
