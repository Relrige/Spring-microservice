package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.order.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.order.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ownership end to end (security chain, {@link OrderAccess}, PostgreSQL): a customer reaches their own order, and
 * another customer's order is answered exactly like a missing one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, KafkaTestContainerConfig.class})
class OrderAccessIntegrationTest {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID alicesOrder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        alicesOrder = repository.saveAndFlush(OrderEntity.place(UUID.randomUUID(), ALICE, "alice@example.com",
                new DeliveryDetails("Alice", "+380501234567", "Kyiv, branch 1", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 1)),
                "UAH", Instant.now(), Duration.ofMinutes(15))).getId();
    }

    @Test
    void theOwner_seesTheOrder() throws Exception {
        mockMvc.perform(get("/orders/" + alicesOrder).headers(TrustedHeaders.asCustomer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(alicesOrder.toString()));
    }

    @Test
    void anotherCustomer_getsTheSameAnswerAsForAMissingOrder() throws Exception {
        String foreign = mockMvc.perform(get("/orders/" + alicesOrder).headers(TrustedHeaders.asCustomer(BOB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andReturn().getResponse().getContentAsString();
        UUID missingId = UUID.randomUUID();
        String missing = mockMvc.perform(get("/orders/" + missingId).headers(TrustedHeaders.asCustomer(BOB)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Same shape and wording; only the ID and the timestamp differ
        assertThat(normalize(foreign, alicesOrder)).isEqualTo(normalize(missing, missingId));
    }

    private static String normalize(String problem, UUID orderId) {
        return problem.replace(orderId.toString(), "<id>").replaceAll("\"timestamp\":\"[^\"]+\"", "");
    }
}
