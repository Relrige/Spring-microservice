package ua.edu.ukma.springers.voltstore.delivery.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;
import ua.edu.ukma.springers.voltstore.delivery.support.TrustedHeaders;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Task 12 (EP-DEL-01) against a real database; the TTN job is off so shipments keep the status the test gives them
@SpringBootTest(properties = "delivery.ttn-job.enabled=false")
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class BatchGetShipmentsIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
    }

    @Test
    void mixedFoundAndMissing_inRequestOrder_withDuplicatesCollapsed() throws Exception {
        Shipment failed = Shipments.pending(UUID.randomUUID());
        failed.markFailed("packaging_data_missing:x");
        repository.save(failed);
        Shipment inTransit = Shipments.pending(UUID.randomUUID());
        inTransit.markGenerated("20450000000001", new Parcel(100, 10, 10, 10));
        inTransit.markInTransit(Instant.now());
        repository.save(inTransit);
        Shipment tombstone = repository.save(Shipment.cancelledTombstone(UUID.randomUUID()));
        UUID missing = UUID.randomUUID();

        batchGet(String.join(",", inTransit.getOrderId().toString(), missing.toString(), failed.getOrderId().toString(),
                inTransit.getOrderId().toString(), tombstone.getOrderId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].orderId").value(inTransit.getOrderId().toString()))
                .andExpect(jsonPath("$[0].status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$[0].ttn").value("20450000000001"))
                .andExpect(jsonPath("$[1].orderId").value(missing.toString()))
                .andExpect(jsonPath("$[1].status").value(nullValue()))
                .andExpect(jsonPath("$[1].ttn").value(nullValue()))
                .andExpect(jsonPath("$[2].status").value("TTN_FAILED"))
                .andExpect(jsonPath("$[2].failureReason").value("packaging_data_missing:x"))
                .andExpect(jsonPath("$[3].status").value("CANCELLED"))
                .andExpect(jsonPath("$[3].ttn").value(nullValue()));
    }

    @Test
    void noShipmentAtAll_everyEntryHasNullStatus() throws Exception {
        batchGet(UUID.randomUUID() + "," + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value(nullValue()))
                .andExpect(jsonPath("$[1].status").value(nullValue()));
    }

    @Test
    void hundredValues_areAccepted_hundredAndOneAreNot() throws Exception {
        batchGet(uuids(100)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        batchGet(uuids(101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderIds").isNotEmpty());
    }

    @Test
    void missingEmptyOrMalformedOrderIds_are400WithErrors() throws Exception {
        mockMvc.perform(get("/delivery/shipments")
                        .headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderIds").isNotEmpty());
        batchGet("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.orderIds").isNotEmpty());
        batchGet(UUID.randomUUID() + ",," + UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.orderIds").isNotEmpty());
        batchGet(UUID.randomUUID() + ",not-a-uuid")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.orderIds").isNotEmpty());
    }

    @Test
    void callersOtherThanServices_areRejected() throws Exception {
        String url = "/delivery/shipments?orderIds=" + UUID.randomUUID();
        mockMvc.perform(get(url))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(url)
                        .headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isForbidden());
    }

    private ResultActions batchGet(String orderIds) throws Exception {
        return mockMvc.perform(get("/delivery/shipments").param("orderIds", orderIds)
                .headers(TrustedHeaders.asInternalService()));
    }

    private static String uuids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
    }
}
