package ua.edu.ukma.springers.voltstore.inventory.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The broker is up but Catalog has not created its topic yet (auto-creation is disabled). The listener must
 * not stop the application: {@code missing-topics-fatal} is false by default, so the container keeps running
 * and subscribes once the topic appears.
 */
@SpringBootTest(properties = "inventory.kafka.topics.product-events=catalog.product-events-not-created")
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class ProductCreatedMissingTopicTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void catalogTopicMissing_applicationIsReady_andListenerContainerRuns() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertThat(listenerRegistry.getListenerContainers())
                .isNotEmpty()
                .allMatch(MessageListenerContainer::isRunning);
    }
}
