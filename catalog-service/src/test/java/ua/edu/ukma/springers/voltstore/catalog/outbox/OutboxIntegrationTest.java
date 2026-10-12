package ua.edu.ukma.springers.voltstore.catalog.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.catalog.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.catalog.support.TopicProbe;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.clean;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.insertCategory;
import static ua.edu.ukma.springers.voltstore.catalog.support.CatalogTestData.productJson;
import static ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders.asManager;

/**
 * Task 19. The scheduled relay is effectively switched off (it runs once at startup, then every hour), and
 * the tests run it by hand, so each step is deterministic. A broker outage is simulated by making the
 * KafkaTemplate's send fail, which is exactly what the relay sees when the broker is unreachable.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {"catalog.outbox.relay-interval=1h", "catalog.outbox.publish-timeout=5s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OutboxIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoSpyBean
    private Outbox outbox;

    @MockitoSpyBean
    // Raw type: Spring Boot declares the bean as KafkaTemplate<?, ?>, which a parameterized field would not match
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    private UUID categoryId;

    @BeforeEach
    void setUp() {
        clean(jdbc);
        categoryId = insertCategory(jdbc, "Laptops");
    }

    @Test
    void brokerDownDuringCreate_eventIsKeptAndPublishedWhenTheBrokerIsBack() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            failAllSends();
            String productId = createProduct();

            relay.publishDueEvents();
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox_events");
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).contains("broker down");

            // Broker back; skip the back-off wait instead of sleeping
            doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
            // Clearly in the past: the relay compares with the JVM clock, which may lag the database container's clock
            jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 hour'");
            relay.publishDueEvents();

            assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events", Boolean.class)).isTrue();
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    probe.poll().stream().anyMatch(record -> productId.equals(record.key())));
        }
    }

    @Test
    void failingEvent_waitsWithBackOffAndDoesNotBlockNewerEvents() throws Exception {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), KafkaTopics.PRODUCT_EVENTS)) {
            String poison = createProduct();
            doAnswer(invocation -> {
                ProducerRecord<?, ?> record = invocation.getArgument(0);
                return poison.equals(record.key())
                        ? CompletableFuture.failedFuture(new KafkaException("rejected"))
                        : invocation.callRealMethod();
            }).when(kafkaTemplate).send(any(ProducerRecord.class));

            relay.publishDueEvents();
            String newer = createProduct();

            // Whenever the failing event is due again it fails again, but between its retries the newer
            // event goes out
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                relay.publishDueEvents();
                return probe.poll().stream().anyMatch(record -> newer.equals(record.key()));
            });
            Map<String, Object> failed = jdbc.queryForMap(
                    "SELECT published_at, attempts FROM outbox_events WHERE aggregate_id = ?::uuid", poison);
            assertThat(failed.get("published_at")).isNull();
            assertThat((Integer) failed.get("attempts")).isPositive();
        }
    }

    @Test
    void failureWhileSavingTheProductEvent_rollsBackTheProduct() throws Exception {
        // Stub the spy behind the transactional proxy: stubbing through the proxy would run its MANDATORY check
        Outbox spy = AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("outbox insert failed")).when(spy).append(any(), any(), any(), any(), any(), any());

        mockMvc.perform(post("/catalog/products").headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(categoryId)))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void failedProductCreate_leavesNoOrphanEvent() throws Exception {
        mockMvc.perform(post("/catalog/products").headers(asManager()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void append_outsideATransaction_isRejected() {
        assertThatThrownBy(() -> outbox.append("Product", UUID.randomUUID(), "ProductCreated",
                KafkaTopics.PRODUCT_EVENTS, UUID.randomUUID(), Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void claimedEvents_areSkippedByASecondRelayInstance() throws Exception {
        appendEvents(3);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Relay A claims all events and keeps its transaction (and the row locks) open
        CompletableFuture<Integer> relayA = CompletableFuture.supplyAsync(() -> transactionTemplate.execute(status -> {
            int count = repository.claimDue(Instant.now(), 10).size();
            claimed.countDown();
            awaitQuietly(release);
            return count;
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        // Relay B neither waits for the locks nor gets the same events
        Integer claimedByB = transactionTemplate.execute(status -> repository.claimDue(Instant.now(), 10).size());
        release.countDown();

        assertThat(relayA.get(10, TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(claimedByB).isZero();
    }

    @Test
    void cleanup_deletesOnlyEventsPublishedLongerAgoThanTheRetention() {
        List<UUID> ids = appendEvents(3);
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '8 days' WHERE id = ?", ids.get(0));
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '1 hour' WHERE id = ?", ids.get(1));

        assertThat(relay.deletePublishedEvents()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT id FROM outbox_events", UUID.class))
                .containsExactlyInAnyOrder(ids.get(1), ids.get(2));
    }

    private String createProduct() throws Exception {
        String body = mockMvc.perform(post("/catalog/products").headers(asManager())
                        .contentType(MediaType.APPLICATION_JSON).content(productJson(categoryId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("productId").asString();
    }

    private void failAllSends() {
        doAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("broker down")))
                .when(kafkaTemplate).send(any(ProducerRecord.class));
    }

    private List<UUID> appendEvents(int count) {
        return transactionTemplate.execute(status -> {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID id = UUID.randomUUID();
                outbox.append("Product", UUID.randomUUID(), "ProductCreated", KafkaTopics.PRODUCT_EVENTS, id,
                        Map.of("eventId", id.toString()));
                ids.add(id);
            }
            return ids;
        });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
