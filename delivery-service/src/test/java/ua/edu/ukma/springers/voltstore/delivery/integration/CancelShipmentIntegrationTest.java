package ua.edu.ukma.springers.voltstore.delivery.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierException;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.events.OrderPaidEvent;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;
import ua.edu.ukma.springers.voltstore.delivery.services.ShipmentService;
import ua.edu.ukma.springers.voltstore.delivery.support.Concurrently;
import ua.edu.ukma.springers.voltstore.delivery.support.OrderEvents;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;
import ua.edu.ukma.springers.voltstore.delivery.support.TrustedHeaders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Task 13 (EP-DEL-02); the TTN job is off so shipments keep the status the test gives them
@SpringBootTest(properties = "delivery.ttn-job.enabled=false")
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class CancelShipmentIntegrationTest {
    private static final String TTN = "20450000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private CarrierGateway carrier;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
        clearInvocations(carrier);
        doCallRealMethod().when(carrier).cancel(any());
    }

    @Test
    void noShipment_storesATombstone_andALateOrderPaidCreatesNothing() throws Exception {
        UUID orderId = UUID.randomUUID();

        cancel(orderId).andExpect(status().isNoContent());

        Shipment tombstone = repository.findByOrderId(orderId).orElseThrow();
        assertThat(tombstone.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(tombstone.isTombstone()).isTrue();

        shipmentService.createFromOrderPaid(jsonMapper.readValue(OrderEvents.orderPaid(orderId),
                OrderPaidEvent.class));
        assertThat(repository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_items", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"TTN_PENDING", "TTN_FAILED"})
    void beforeATtnExists_isCancelledWithoutACarrierCall(ShipmentStatus status) throws Exception {
        Shipment shipment = saved(status);

        cancel(shipment.getOrderId()).andExpect(status().isNoContent());

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        verify(carrier, never()).cancel(any());
    }

    @Test
    void generatedTtn_isCancelledHere_andAtTheCarrier() throws Exception {
        Shipment shipment = saved(ShipmentStatus.TTN_GENERATED);

        cancel(shipment.getOrderId()).andExpect(status().isNoContent());

        Shipment cancelled = reload(shipment);
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getTtn()).isEqualTo(TTN);
        assertThat(cancelled.getFailureReason()).isNull();
        verify(carrier).cancel(TTN);
    }

    @Test
    void carrierCancellationFails_stillNoContent_withTheFailureRecordedAndTheTtnKept() throws Exception {
        doThrow(new CarrierException("simulated carrier failure")).when(carrier).cancel(any());
        Shipment shipment = saved(ShipmentStatus.TTN_GENERATED);

        cancel(shipment.getOrderId()).andExpect(status().isNoContent());

        Shipment cancelled = reload(shipment);
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getTtn()).isEqualTo(TTN);
        assertThat(cancelled.getFailureReason()).isEqualTo("carrier_cancellation_failed: simulated carrier failure");
    }

    @Test
    void repeatedCall_isNoContent_withoutASecondCarrierCall() throws Exception {
        Shipment shipment = saved(ShipmentStatus.TTN_GENERATED);

        cancel(shipment.getOrderId()).andExpect(status().isNoContent());
        cancel(shipment.getOrderId()).andExpect(status().isNoContent());

        verify(carrier).cancel(TTN);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"IN_TRANSIT", "DELIVERED"})
    void handedToTheCarrier_isConflict(ShipmentStatus status) throws Exception {
        Shipment shipment = saved(status);

        cancel(shipment.getOrderId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/conflict"))
                .andExpect(jsonPath("$.detail").value("Cannot cancel a shipment that was handed to the carrier"));

        assertThat(reload(shipment).getStatus()).isEqualTo(status);
        verify(carrier, never()).cancel(any());
    }

    @Test
    void concurrentCancelAndOrderPaid_endWithACancelledShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderPaidEvent paid = jsonMapper.readValue(OrderEvents.orderPaid(orderId), OrderPaidEvent.class);

        List<Object> results = Concurrently.run(
                () -> {
                    shipmentService.cancel(orderId);
                    return "cancelled";
                },
                () -> {
                    shipmentService.createFromOrderPaid(paid);
                    return "paid";
                });

        assertThat(results).noneMatch(Exception.class::isInstance);
        assertThat(repository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void nonUuidOrderId_isBadRequest_andOnlyServicesMayCall() throws Exception {
        mockMvc.perform(delete("/delivery/shipments/not-a-uuid").headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/delivery/shipments/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/delivery/shipments/" + UUID.randomUUID())
                        .headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isForbidden());
    }

    private ResultActions cancel(UUID orderId) throws Exception {
        return mockMvc.perform(delete("/delivery/shipments/" + orderId).headers(TrustedHeaders.asInternalService()));
    }

    private Shipment saved(ShipmentStatus status) {
        Shipment shipment = Shipments.pending(UUID.randomUUID());
        switch (status) {
            case TTN_PENDING -> {
            }
            case TTN_FAILED -> shipment.markFailed("carrier_error: down");
            case TTN_GENERATED -> shipment.markGenerated(TTN, new Parcel(100, 10, 10, 10));
            case IN_TRANSIT -> {
                shipment.markGenerated(TTN, new Parcel(100, 10, 10, 10));
                shipment.markInTransit(Instant.now());
            }
            case DELIVERED -> {
                shipment.markGenerated(TTN, new Parcel(100, 10, 10, 10));
                shipment.markInTransit(Instant.now());
                shipment.markDelivered();
            }
            case CANCELLED -> shipment.cancel();
        }
        return repository.save(shipment);
    }

    private Shipment reload(Shipment shipment) {
        return repository.findById(shipment.getId()).orElseThrow();
    }
}
