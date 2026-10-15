package ua.edu.ukma.springers.voltstore.delivery.integration;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Order does not publish yet, so its topic may be missing: Delivery must still start, listen and stay ready
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class MissingSourceTopicsStartupTest {
    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listenerRunsAndServiceIsReady_withoutOrdersTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS))
                    .doesNotContain("order.order-events")
                    .contains("delivery.shipment-events", "delivery.order.order-events.dlt");
        }

        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty()
                .allSatisfy(container -> assertThat(container.isRunning()).isTrue());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
