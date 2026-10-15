package ua.edu.ukma.springers.voltstore.delivery.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;
import ua.edu.ukma.springers.voltstore.delivery.support.OrderEvents;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

/**
 * Task 10 (EP-DEL-EVT-01). The test plays Order Service with a plain Kafka producer and the payload from the Event
 * Catalogue. The TTN generation job is switched off, so the created shipments stay {@code TTN_PENDING}.
 */
@SpringBootTest(properties = "delivery.ttn-job.enabled=false")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, OrderEvents.SourceTopic.class})
class OrderPaidConsumerIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    // Lets a test make the database fail once, like a short outage
    @MockitoSpyBean
    private ShipmentRepository repository;

    private OrderEvents orderEvents;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
        orderEvents = new OrderEvents(kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        orderEvents.close();
    }

    @Test
    void orderPaid_createsOnePendingShipmentWithRecipientItemsAndDeclaredValue() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID productA = UUID.randomUUID();
        UUID productB = UUID.randomUUID();

        orderEvents.sendOrderPaid(orderId, productA, productB);

        Shipment shipment = awaitShipment(orderId);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.TTN_PENDING);
        assertThat(shipment.getRecipientName()).isEqualTo("Ivan Petrenko");
        assertThat(shipment.getRecipientPhone()).isEqualTo("+380501234567");
        assertThat(shipment.getRecipientAddress()).isEqualTo("Kyiv, branch 12");
        assertThat(shipment.getCarrier()).isEqualTo("NOVA_POSHTA");
        assertThat(shipment.getDeclaredValue()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(shipment.getCurrency()).isEqualTo("UAH");
        assertThat(shipment.getAttempts()).isZero();
        assertThat(shipment.getNextAttemptAt()).isNotNull();
        assertThat(jdbc.queryForList("SELECT product_id FROM shipment_items si JOIN shipments s ON s.id = "
                + "si.shipment_id WHERE s.order_id = ?", UUID.class, orderId))
                .containsExactlyInAnyOrder(productA, productB);
        assertThat(jdbc.queryForList("SELECT quantity FROM shipment_items si JOIN shipments s ON s.id = "
                + "si.shipment_id WHERE s.order_id = ?", Integer.class, orderId)).containsOnly(2);
    }

    @Test
    void sameEventTwice_createsOneShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        String event = OrderEvents.orderPaid(orderId);

        orderEvents.send(orderId, "OrderPaid", event);
        orderEvents.send(orderId, "OrderPaid", event);
        UUID marker = sendMarkerAfter(orderId);

        awaitShipment(marker);
        assertThat(count(orderId)).isEqualTo(1);
    }

    @Test
    void orderPaidAfterACancellationTombstone_staysCancelled() throws Exception {
        UUID orderId = UUID.randomUUID();
        repository.save(Shipment.cancelledTombstone(orderId));

        orderEvents.sendOrderPaid(orderId);
        UUID marker = sendMarkerAfter(orderId);

        awaitShipment(marker);
        Shipment shipment = repository.findByOrderId(orderId).orElseThrow();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.isTombstone()).isTrue();
    }

    @Test
    void otherEventTypes_areSkipped() throws Exception {
        UUID orderId = UUID.randomUUID();

        orderEvents.send(orderId, "OrderCancelled", OrderEvents.orderPaid(orderId));
        orderEvents.send(orderId, null, OrderEvents.orderPaid(orderId));
        UUID marker = sendMarkerAfter(orderId);

        awaitShipment(marker);
        assertThat(count(orderId)).isZero();
    }

    @Test
    void poisonMessages_goToTheDeadLetterTopicAtOnce_andDoNotBlockLaterMessages() throws Exception {
        UUID key = UUID.randomUUID();
        String withoutItems = OrderEvents.orderPaid(key).replaceAll("\"items\":\\[[^]]*]", "\"items\":[]");
        String withoutAddress = OrderEvents.orderPaid(key).replace("\"address\":\"Kyiv, branch 12\",", "");
        String zeroQuantity = OrderEvents.orderPaid(key).replace("\"quantity\":2", "\"quantity\":0");

        orderEvents.send(key, "OrderPaid", "this is not JSON");
        orderEvents.send(key, "OrderPaid", withoutItems);
        orderEvents.send(key, "OrderPaid", withoutAddress);
        orderEvents.send(key, "OrderPaid", zeroQuantity);
        long start = System.nanoTime();
        UUID marker = sendMarkerAfter(key);

        awaitShipment(marker);
        // No back-off retries (4 retries would take 15 s): poison messages are not retried
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(count(key)).isZero();
        await().atMost(WAIT).untilAsserted(() -> assertThat(orderEvents.deadLetters(key))
                .containsExactly("this is not JSON", withoutItems, withoutAddress, zeroQuantity));
    }

    @Test
    void transientDatabaseFailure_isRetried() throws Exception {
        UUID orderId = UUID.randomUUID();
        // The repository is an interface proxy: "real method" = the spy's delegating default answer
        Answer<?> real = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        doThrow(new TransientDataAccessResourceException("database restarting")).doAnswer(real)
                .when(repository).saveAndFlush(any());

        orderEvents.sendOrderPaid(orderId);

        assertThat(awaitShipment(orderId).getStatus()).isEqualTo(ShipmentStatus.TTN_PENDING);
        assertThat(orderEvents.deadLetters(orderId)).isEmpty();
    }

    // Same key → same partition: once the marker's shipment exists, every earlier record of that key was processed
    private UUID sendMarkerAfter(UUID key) throws Exception {
        UUID marker = UUID.randomUUID();
        orderEvents.send(key, "OrderPaid", OrderEvents.orderPaid(marker));
        return marker;
    }

    private Shipment awaitShipment(UUID orderId) {
        await().atMost(WAIT).until(() -> repository.findByOrderId(orderId).isPresent());
        return repository.findByOrderId(orderId).orElseThrow();
    }

    private Integer count(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
    }
}
