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
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=localhost:1")
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
