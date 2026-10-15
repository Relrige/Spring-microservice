package ua.edu.ukma.springers.voltstore.delivery.services;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierException;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;
import ua.edu.ukma.springers.voltstore.delivery.support.Concurrently;
import ua.edu.ukma.springers.voltstore.delivery.support.MutableClock;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Task 11. Real PostgreSQL, Kafka and mock carrier (spied, to inject failures), WireMock as Inventory, and a clock
 * the test moves: the scheduled run is effectively switched off (once at startup, then hourly) and the test calls
 * {@link TtnGenerationJob#runOnce()} by hand.
 */
@SpringBootTest(properties = "delivery.ttn-job.interval=1h")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, MutableClock.Config.class})
class TtnGenerationJobIntegrationTest {
    private static final String PACKAGING = "/inventory/stock/[^/]+/packaging";

    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("clients.inventory.url", inventory::baseUrl);
    }

    @Autowired
    private TtnGenerationJob job;

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private CarrierGateway carrier;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
        clock.reset();
        clearInvocations(carrier);
        inventory.resetAll();
        inventory.stubFor(get(urlPathMatching(PACKAGING)).willReturn(okJson(
                "{\"grossWeightGrams\":180,\"lengthCm\":22,\"widthCm\":12,\"heightCm\":6}")));
    }

    @Test
    void happyPath_generatesTheTtn_storesTheParcel_andAppendsOneTtnGeneratedEvent() {
        Shipment shipment = pending();

        assertThat(job.runOnce()).isEqualTo(1);

        Shipment generated = reload(shipment);
        assertThat(generated.getStatus()).isEqualTo(ShipmentStatus.TTN_GENERATED);
        assertThat(generated.getTtn()).matches("\\d{14}");
        // One unit of 22x12x6 at 180 g
        assertThat(List.of(generated.getTotalWeightGrams(), generated.getLengthCm(), generated.getWidthCm(),
                generated.getHeightCm())).containsExactly(180, 22, 12, 6);
        assertThat(generated.getFailureReason()).isNull();
        List<Map<String, Object>> events = outboxRows(shipment);
        assertThat(events).singleElement().satisfies(row -> {
            assertThat(row.get("event_type")).isEqualTo("TTNGenerated");
            assertThat(row.get("payload").toString()).contains(generated.getTtn());
        });

        // Nothing is due any more
        clock.advance(Duration.ofHours(1));
        assertThat(job.runOnce()).isZero();
    }

    @Test
    void carrierFailsTwiceThenSucceeds_generatedOnTheThirdAttemptAfterTheBackOffs() {
        doThrow(new CarrierException("timeout")).doThrow(new CarrierException("timeout")).doCallRealMethod()
                .when(carrier).register(any());
        Shipment shipment = pending();
        Instant start = clock.instant();

        job.runOnce();
        assertThat(reload(shipment).getAttempts()).isEqualTo(1);
        assertThat(reload(shipment).getFailureReason()).isEqualTo("carrier_error: timeout");
        assertThat(reload(shipment).getNextAttemptAt()).isEqualTo(start.plusSeconds(5));

        // Not due yet
        clock.advance(Duration.ofSeconds(4));
        assertThat(job.runOnce()).isZero();

        clock.advance(Duration.ofSeconds(1));
        job.runOnce();
        assertThat(reload(shipment).getAttempts()).isEqualTo(2);
        assertThat(reload(shipment).getNextAttemptAt()).isEqualTo(clock.instant().plusSeconds(30));

        clock.advance(Duration.ofSeconds(30));
        job.runOnce();
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.TTN_GENERATED);
        assertThat(outboxRows(shipment)).hasSize(1);
        verify(carrier, times(3)).register(any());
    }

    @Test
    void carrierAlwaysFails_shipmentIsTtnFailedAfterFourAttempts() {
        doThrow(new CarrierException("carrier down")).when(carrier).register(any());
        Shipment shipment = pending();

        job.runOnce();
        for (Duration backoff : List.of(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2))) {
            clock.advance(backoff);
            assertThat(job.runOnce()).isEqualTo(1);
        }

        Shipment failed = reload(shipment);
        assertThat(failed.getStatus()).isEqualTo(ShipmentStatus.TTN_FAILED);
        assertThat(failed.getAttempts()).isEqualTo(4);
        assertThat(failed.getFailureReason()).isEqualTo("carrier_error: carrier down");
        assertThat(outboxRows(shipment)).isEmpty();
        clock.advance(Duration.ofHours(1));
        assertThat(job.runOnce()).isZero();
    }

    @Test
    void inventory422_isTtnFailedAfterOneAttempt() {
        UUID productId = UUID.randomUUID();
        inventory.stubFor(get(urlPathEqualTo("/inventory/stock/" + productId + "/packaging"))
                .willReturn(aResponse().withStatus(422)));
        Shipment shipment = repository.save(Shipments.pending(UUID.randomUUID(), clock.instant(), productId));

        job.runOnce();

        Shipment failed = reload(shipment);
        assertThat(failed.getStatus()).isEqualTo(ShipmentStatus.TTN_FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getFailureReason()).isEqualTo("packaging_data_missing:" + productId);
        verify(carrier, never()).register(any());
    }

    @Test
    void inventoryDown_isRetriedLikeACarrierFailure() {
        inventory.stubFor(get(urlPathMatching(PACKAGING)).willReturn(aResponse().withStatus(503)));
        Shipment shipment = pending();

        job.runOnce();

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.TTN_PENDING);
        assertThat(reload(shipment).getFailureReason()).isEqualTo("inventory_unavailable");
        assertThat(reload(shipment).getAttempts()).isEqualTo(1);
    }

    @Test
    void cancelledDuringTheAttempt_staysCancelled_noEvent_andTheNewTtnIsCancelledAtTheCarrier() {
        Shipment shipment = pending();
        List<String> issued = new ArrayList<>();
        doAnswer(invocation -> {
            // The cancellation saga commits while the carrier call runs
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    repository.findById(shipment.getId()).orElseThrow().cancel());
            String ttn = (String) invocation.callRealMethod();
            issued.add(ttn);
            return ttn;
        }).when(carrier).register(any());

        job.runOnce();

        Shipment cancelled = reload(shipment);
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(cancelled.getTtn()).isNull();
        assertThat(outboxRows(shipment)).isEmpty();
        assertThat(issued).hasSize(1);
        verify(carrier).cancel(issued.getFirst());
    }

    @Test
    void twoJobInstancesAtOnce_registerEachShipmentOnce() throws Exception {
        List<Shipment> shipments = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            shipments.add(pending());
        }

        List<Object> claimed = Concurrently.run(2, job::runOnce);

        assertThat(claimed).noneMatch(Exception.class::isInstance);
        assertThat(claimed.stream().mapToInt(count -> (Integer) count).sum()).isEqualTo(8);
        verify(carrier, times(8)).register(any());
        assertThat(shipments).allSatisfy(shipment ->
                assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.TTN_GENERATED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(8);
    }

    @Test
    void shipmentsDueInTheFuture_areNotPicked() {
        Shipment later = repository.save(Shipments.pending(UUID.randomUUID(), clock.instant().plusSeconds(60)));

        assertThat(job.runOnce()).isZero();

        assertThat(reload(later).getStatus()).isEqualTo(ShipmentStatus.TTN_PENDING);
        verify(carrier, never()).register(any());
    }

    private Shipment pending() {
        return repository.save(Shipments.pending(UUID.randomUUID(), clock.instant()));
    }

    private Shipment reload(Shipment shipment) {
        return repository.findById(shipment.getId()).orElseThrow();
    }

    private List<Map<String, Object>> outboxRows(Shipment shipment) {
        return jdbc.queryForList("SELECT * FROM outbox_events WHERE aggregate_id = ?", shipment.getOrderId());
    }
}
