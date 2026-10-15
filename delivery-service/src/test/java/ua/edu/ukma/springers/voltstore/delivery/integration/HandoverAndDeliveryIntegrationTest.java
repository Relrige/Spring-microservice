package ua.edu.ukma.springers.voltstore.delivery.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierEventHandler;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;
import ua.edu.ukma.springers.voltstore.delivery.services.HandoverRecovery;
import ua.edu.ukma.springers.voltstore.delivery.support.OrderEvents;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;
import ua.edu.ukma.springers.voltstore.delivery.support.TopicProbe;
import ua.edu.ukma.springers.voltstore.delivery.support.TrustedHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 15 (EP-DEL-EVT-02, EP-DEL-04): {@code OrderShipped} → {@code IN_TRANSIT} → handover → the mock carrier
 * reports the delivery after 1 s → {@code DELIVERED} and {@code OrderDelivered}. The outbox relay runs as configured,
 * so the event is checked on the topic. The TTN job is off.
 */
@SpringBootTest(properties = {"delivery.ttn-job.enabled=false", "delivery.carrier.mock.delivery-delay=1s"})
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, OrderEvents.SourceTopic.class})
class HandoverAndDeliveryIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private CarrierEventHandler deliveryReports;

    @Autowired
    private HandoverRecovery handoverRecovery;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private CarrierGateway carrier;

    private OrderEvents orderEvents;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
        clearInvocations(carrier);
        orderEvents = new OrderEvents(kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        orderEvents.close();
    }

    @Test
    void orderShipped_inTransitWithHandoverTime_thenDelivered_withExactlyOneOrderDeliveredEvent() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.SHIPMENT_EVENTS)) {
            Shipment shipment = generated();
            Instant before = Instant.now();

            orderEvents.sendOrderShipped(shipment.getOrderId(), shipment.getTtn());

            await().atMost(WAIT).until(() -> reload(shipment).getStatus() == ShipmentStatus.IN_TRANSIT
                    || reload(shipment).getStatus() == ShipmentStatus.DELIVERED);
            assertThat(reload(shipment).getHandedOverAt()).isAfterOrEqualTo(before.minusSeconds(1));
            await().atMost(WAIT).until(() -> reload(shipment).getStatus() == ShipmentStatus.DELIVERED);

            ConsumerRecord<String, String> record = await().atMost(WAIT).until(() -> probe.poll().stream()
                    .filter(r -> r.key().equals(shipment.getOrderId().toString())).toList(),
                    records -> !records.isEmpty()).getFirst();
            assertThat(header(record, KafkaTopics.EVENT_TYPE_HEADER)).isEqualTo("OrderDelivered");
            JsonNode value = JSON.readTree(record.value());
            assertThat(value.propertyNames()).containsExactlyInAnyOrder("eventId", "orderId", "ttn", "timestamp");
            assertThat(value.get("orderId").asString()).isEqualTo(shipment.getOrderId().toString());
            assertThat(value.get("ttn").asString()).isEqualTo(shipment.getTtn());
            assertThat(header(record, KafkaTopics.EVENT_ID_HEADER)).isEqualTo(value.get("eventId").asString());
            assertThat(orderDeliveredRows(shipment)).isEqualTo(1);
        }
    }

    @Test
    void sameOrderShippedTwice_oneHandover_oneEvent() throws Exception {
        Shipment shipment = generated();

        orderEvents.sendOrderShipped(shipment.getOrderId(), shipment.getTtn());
        orderEvents.sendOrderShipped(shipment.getOrderId(), shipment.getTtn());

        await().atMost(WAIT).until(() -> reload(shipment).getStatus() == ShipmentStatus.DELIVERED);
        // Let the duplicate be processed as well: a marker of the same key after it
        Shipment marker = generated();
        orderEvents.send(shipment.getOrderId(), "OrderShipped",
                OrderEvents.orderShipped(marker.getOrderId(), marker.getTtn()));
        await().atMost(WAIT).until(() -> reload(marker).getStatus() != ShipmentStatus.TTN_GENERATED);
        verify(carrier, times(1)).handOver(shipment.getTtn());
        assertThat(orderDeliveredRows(shipment)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"TTN_PENDING", "TTN_FAILED", "CANCELLED"})
    void orderShippedForAShipmentWithoutTtn_changesNothing(ShipmentStatus status) throws Exception {
        Shipment shipment = Shipments.pending(UUID.randomUUID());
        if (status == ShipmentStatus.TTN_FAILED) {
            shipment.markFailed("carrier_error: down");
        } else if (status == ShipmentStatus.CANCELLED) {
            shipment.cancel();
        }
        repository.save(shipment);

        orderEvents.sendOrderShipped(shipment.getOrderId(), "20450000000000");
        Shipment marker = generated();
        orderEvents.send(shipment.getOrderId(), "OrderShipped",
                OrderEvents.orderShipped(marker.getOrderId(), marker.getTtn()));

        await().atMost(WAIT).until(() -> reload(marker).getStatus() != ShipmentStatus.TTN_GENERATED);
        assertThat(reload(shipment).getStatus()).isEqualTo(status);
        assertThat(reload(shipment).getHandedOverAt()).isNull();
    }

    @Test
    void ttnInTheEventDiffers_theShipmentsOwnTtnIsHandedOver() throws Exception {
        Shipment shipment = generated();

        orderEvents.sendOrderShipped(shipment.getOrderId(), "99999999999999");

        await().atMost(WAIT).until(() -> reload(shipment).getStatus() == ShipmentStatus.DELIVERED);
        verify(carrier).handOver(shipment.getTtn());
        verify(carrier, never()).handOver("99999999999999");
    }

    @Test
    void deliveryReportTwiceAndForAnUnknownTtn_oneEventAndNoError() {
        Shipment shipment = generated();
        shipment = repository.findById(shipment.getId()).orElseThrow();
        shipment.markInTransit(Instant.now());
        repository.save(shipment);

        deliveryReports.onDelivered(shipment.getTtn());
        deliveryReports.onDelivered(shipment.getTtn());
        deliveryReports.onDelivered("00000000000000");

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(orderDeliveredRows(shipment)).isEqualTo(1);
    }

    @Test
    void deliveryReportForAShipmentNotInTransit_changesNothing() {
        Shipment shipment = generated();

        deliveryReports.onDelivered(shipment.getTtn());

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.TTN_GENERATED);
        assertThat(orderDeliveredRows(shipment)).isZero();
    }

    @Test
    void restartRecovery_inTransitShipmentWhoseReportWasLost_endsDelivered() {
        // IN_TRANSIT in the database, but this carrier instance never got the handover (as after a restart)
        Shipment shipment = generated();
        shipment = repository.findById(shipment.getId()).orElseThrow();
        shipment.markInTransit(Instant.now());
        repository.save(shipment);
        Shipment saved = shipment;

        handoverRecovery.handOverInTransitShipmentsAgain();

        await().atMost(WAIT).until(() -> reload(saved).getStatus() == ShipmentStatus.DELIVERED);
        assertThat(orderDeliveredRows(saved)).isEqualTo(1);
    }

    @Test
    void cancellingAnInTransitShipment_isConflict() throws Exception {
        Shipment shipment = generated();
        shipment = repository.findById(shipment.getId()).orElseThrow();
        shipment.markInTransit(Instant.now());
        repository.save(shipment);

        mockMvc.perform(delete("/delivery/shipments/" + shipment.getOrderId())
                        .headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isConflict());
        verify(carrier, never()).cancel(any());
    }

    private Shipment generated() {
        Shipment shipment = Shipments.pending(UUID.randomUUID());
        shipment.markGenerated(randomTtn(), new Parcel(100, 10, 10, 10));
        return repository.save(shipment);
    }

    private Shipment reload(Shipment shipment) {
        return repository.findById(shipment.getId()).orElseThrow();
    }

    private int orderDeliveredRows(Shipment shipment) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, shipment.getOrderId(), "OrderDelivered");
    }

    private static String randomTtn() {
        return Long.toString(ThreadLocalRandom.current().nextLong(10_000_000_000_000L, 100_000_000_000_000L));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
