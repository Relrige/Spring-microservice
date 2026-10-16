package ua.edu.ukma.springers.voltstore.customer.integration;

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
import ua.edu.ukma.springers.voltstore.customer.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.entities.CartLine;
import ua.edu.ukma.springers.voltstore.customer.repositories.CartRepository;
import ua.edu.ukma.springers.voltstore.customer.services.CartService;
import ua.edu.ukma.springers.voltstore.customer.support.CartApi;
import ua.edu.ukma.springers.voltstore.customer.support.OrderEvents;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

/**
 * Task 08 (EP-CUST-EVT-01). The test plays Order Service with a plain Kafka producer and the payload from the Event
 * Catalogue. Records with the same key go to the same partition and are processed in order, so once a later
 * "marker" event has been processed, every earlier record of that key has been too.
 */
@SpringBootTest
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, OrderEvents.SourceTopic.class})
class OrderPaidConsumerIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CartService cartService;

    // Lets a test make the database fail once, like a short outage
    @MockitoSpyBean
    private CartRepository cartRepository;

    private OrderEvents orderEvents;

    @BeforeEach
    void setUp() {
        CartApi.cleanTables(jdbc);
        orderEvents = new OrderEvents(kafka.getBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        orderEvents.close();
    }

    @Test
    void orderPaid_clearsTheCustomersCart_andKeepsTheCartRow() throws Exception {
        UUID customerId = cartWithItems();
        UUID otherCustomer = cartWithItems();
        UUID orderId = UUID.randomUUID();

        orderEvents.send(orderId, "OrderPaid", OrderEvents.orderPaid(orderId, customerId));

        await().atMost(WAIT).until(() -> cartService.getCart(customerId).isEmpty());
        assertThat(cartRowExists(customerId)).isTrue();
        assertThat(cartService.getCart(otherCustomer)).hasSize(2);
    }

    @Test
    void sameEventTwice_isHarmless() throws Exception {
        UUID customerId = cartWithItems();
        UUID orderId = UUID.randomUUID();
        String event = OrderEvents.orderPaid(orderId, customerId);

        orderEvents.send(orderId, "OrderPaid", event);
        orderEvents.send(orderId, "OrderPaid", event);
        awaitMarkerAfter(orderId);

        assertThat(cartService.getCart(customerId)).isEmpty();
        assertThat(orderEvents.deadLetters(orderId)).isEmpty();
    }

    @Test
    void guestOrder_changesNoCart() throws Exception {
        UUID customerId = cartWithItems();
        UUID orderId = UUID.randomUUID();

        orderEvents.send(orderId, "OrderPaid", OrderEvents.orderPaid(orderId, null));
        awaitMarkerAfter(orderId);

        assertThat(cartService.getCart(customerId)).hasSize(2);
        assertThat(orderEvents.deadLetters(orderId)).isEmpty();
    }

    @Test
    void customerWithoutCart_getsNoCartRow() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        orderEvents.send(orderId, "OrderPaid", OrderEvents.orderPaid(orderId, customerId));
        awaitMarkerAfter(orderId);

        assertThat(cartRowExists(customerId)).isFalse();
        assertThat(orderEvents.deadLetters(orderId)).isEmpty();
    }

    @Test
    void otherEventTypes_areSkipped() throws Exception {
        UUID customerId = cartWithItems();
        UUID orderId = UUID.randomUUID();

        orderEvents.send(orderId, "OrderCancelled", OrderEvents.orderPaid(orderId, customerId));
        orderEvents.send(orderId, null, OrderEvents.orderPaid(orderId, customerId));
        awaitMarkerAfter(orderId);

        assertThat(cartService.getCart(customerId)).hasSize(2);
    }

    @Test
    void poisonMessages_goToTheDeadLetterTopicAtOnce_andDoNotBlockLaterMessages() throws Exception {
        UUID key = UUID.randomUUID();
        UUID customerId = cartWithItems();
        String withoutOrderId = OrderEvents.orderPaid(key, customerId).replaceFirst("\"orderId\":\"[^\"]*\",", "");
        String notAUuid = OrderEvents.orderPaid(key, customerId).replace("\"customerId\":\"" + customerId + "\"",
                "\"customerId\":\"not-a-uuid\"");

        orderEvents.send(key, "OrderPaid", "this is not JSON");
        orderEvents.send(key, "OrderPaid", withoutOrderId);
        orderEvents.send(key, "OrderPaid", notAUuid);
        long start = System.nanoTime();
        awaitMarkerAfter(key);

        // No back-off retries (4 retries would take 15 s): poison messages are not retried
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(cartService.getCart(customerId)).hasSize(2);
        await().atMost(WAIT).untilAsserted(() -> assertThat(orderEvents.deadLetters(key))
                .containsExactly("this is not JSON", withoutOrderId, notAUuid));
    }

    @Test
    void transientDatabaseFailure_isRetried() throws Exception {
        UUID customerId = cartWithItems();
        UUID orderId = UUID.randomUUID();
        // The repository is an interface proxy: "real method" = the spy's delegating default answer
        Answer<?> real = mockingDetails(cartRepository).getMockCreationSettings().getDefaultAnswer();
        doThrow(new TransientDataAccessResourceException("database restarting")).doAnswer(real)
                .when(cartRepository).findByIdForUpdate(any());

        orderEvents.send(orderId, "OrderPaid", OrderEvents.orderPaid(orderId, customerId));

        await().atMost(WAIT).until(() -> cartService.getCart(customerId).isEmpty());
        assertThat(orderEvents.deadLetters(orderId)).isEmpty();
    }

    private UUID cartWithItems() {
        UUID customerId = UUID.randomUUID();
        cartService.replaceCart(customerId, List.of(new CartLine(UUID.randomUUID(), 1),
                new CartLine(UUID.randomUUID(), 2)));
        return customerId;
    }

    // Sends an OrderPaid for a fresh cart with the same key and waits until that cart is cleared
    private void awaitMarkerAfter(UUID key) throws Exception {
        UUID marker = cartWithItems();
        orderEvents.send(key, "OrderPaid", OrderEvents.orderPaid(UUID.randomUUID(), marker));
        await().atMost(WAIT).until(() -> cartService.getCart(marker).isEmpty());
    }

    private boolean cartRowExists(UUID customerId) {
        return jdbc.queryForObject("SELECT count(*) FROM carts WHERE customer_id = ?", Integer.class,
                customerId) == 1;
    }
}
