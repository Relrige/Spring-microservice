package ua.edu.ukma.springers.voltstore.delivery.integration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ua.edu.ukma.springers.voltstore.delivery.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierException;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;
import ua.edu.ukma.springers.voltstore.delivery.support.Concurrently;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;
import ua.edu.ukma.springers.voltstore.delivery.support.TrustedHeaders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Task 14 (EP-DEL-03): WireMock as Inventory, a spied mock carrier, the TTN job off
@SpringBootTest(properties = "delivery.ttn-job.enabled=false")
@AutoConfigureMockMvc
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class ManualTtnRetryIntegrationTest {

    @RegisterExtension
    static WireMockExtension inventory = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("clients.inventory.url", inventory::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private CarrierGateway carrier;

    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
        clearInvocations(carrier);
        doCallRealMethod().when(carrier).register(any());
        inventory.resetAll();
    }

    @Test
    void packagingFixedInInventory_retrySucceeds_withTtnParcelAndEvent() throws Exception {
        Shipment shipment = failed("packaging_data_missing:" + productId);
        packagingIsConfigured();

        String body = retry(shipment.getOrderId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttn").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        Shipment generated = reload(shipment);
        assertThat(generated.getStatus()).isEqualTo(ShipmentStatus.TTN_GENERATED);
        assertThat(body).contains(generated.getTtn());
        assertThat(generated.getFailureReason()).isNull();
        assertThat(generated.getHeightCm()).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ?", String.class,
                shipment.getOrderId())).containsExactly("TTNGenerated");
        // Packaging was fetched again, not taken from anywhere else
        inventory.verify(1, getRequestedFor(urlPathEqualTo(packagingPath())));
    }

    @Test
    void packagingStillMissing_is424WithReason_andStaysFailed() throws Exception {
        Shipment shipment = failed("carrier_error: down");
        inventory.stubFor(get(urlPathEqualTo(packagingPath())).willReturn(aResponse().withStatus(422)));

        retry(shipment.getOrderId())
                .andExpect(status().isFailedDependency())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/ttn-generation-failed"))
                .andExpect(jsonPath("$.reason").value("packaging_data_missing:" + productId));

        Shipment stillFailed = reload(shipment);
        assertThat(stillFailed.getStatus()).isEqualTo(ShipmentStatus.TTN_FAILED);
        assertThat(stillFailed.getFailureReason()).isEqualTo("packaging_data_missing:" + productId);
        verify(carrier, never()).register(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void carrierStillFailing_is424WithReason() throws Exception {
        Shipment shipment = failed("carrier_error: down");
        packagingIsConfigured();
        doThrow(new CarrierException("simulated carrier failure")).when(carrier).register(any());

        retry(shipment.getOrderId())
                .andExpect(status().isFailedDependency())
                .andExpect(jsonPath("$.reason").value("carrier_error: simulated carrier failure"));

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.TTN_FAILED);
        assertThat(reload(shipment).getFailureReason()).isEqualTo("carrier_error: simulated carrier failure");
    }

    @Test
    void noShipment_is404() throws Exception {
        retry(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"));
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "TTN_FAILED", mode = EnumSource.Mode.EXCLUDE)
    void otherStatuses_are409(ShipmentStatus status) throws Exception {
        Shipment shipment = Shipments.pending(UUID.randomUUID(), productId);
        switch (status) {
            case TTN_GENERATED -> shipment.markGenerated("20450000000001", new Parcel(1, 1, 1, 1));
            case IN_TRANSIT -> {
                shipment.markGenerated("20450000000001", new Parcel(1, 1, 1, 1));
                shipment.markInTransit(Instant.now());
            }
            case DELIVERED -> {
                shipment.markGenerated("20450000000001", new Parcel(1, 1, 1, 1));
                shipment.markInTransit(Instant.now());
                shipment.markDelivered();
            }
            case CANCELLED -> shipment.cancel();
            default -> {
            }
        }
        repository.save(shipment);

        retry(shipment.getOrderId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Retry is only allowed for shipments in TTN_FAILED status"));
        verify(carrier, never()).register(any());
    }

    @Test
    void twoConcurrentRetries_oneWins_theLoserGets409_andItsTtnIsCancelled() throws Exception {
        Shipment shipment = failed("carrier_error: down");
        packagingIsConfigured();
        // Both retries reach the carrier before either applies its result
        CountDownLatch bothRegistering = new CountDownLatch(2);
        List<String> issued = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            bothRegistering.countDown();
            bothRegistering.await(10, TimeUnit.SECONDS);
            String ttn = (String) invocation.callRealMethod();
            issued.add(ttn);
            return ttn;
        }).when(carrier).register(any());

        List<Object> statuses = Concurrently.run(2, () ->
                retry(shipment.getOrderId()).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(issued).hasSize(2);
        Shipment generated = reload(shipment);
        assertThat(generated.getStatus()).isEqualTo(ShipmentStatus.TTN_GENERATED);
        assertThat(issued).contains(generated.getTtn());
        String loserTtn = issued.stream().filter(ttn -> !ttn.equals(generated.getTtn())).findFirst().orElseThrow();
        verify(carrier).cancel(loserTtn);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void onlyInventoryWorkersMayRetry() throws Exception {
        String url = "/delivery/orders/" + UUID.randomUUID() + "/ttn/retry";
        mockMvc.perform(post(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(url).headers(TrustedHeaders.asUser("ADMIN"))).andExpect(status().isForbidden());
        mockMvc.perform(post(url).headers(TrustedHeaders.asInternalService())).andExpect(status().isForbidden());
    }

    private Shipment failed(String reason) {
        Shipment shipment = Shipments.pending(UUID.randomUUID(), productId);
        shipment.markFailed(reason);
        return repository.save(shipment);
    }

    private void packagingIsConfigured() {
        inventory.stubFor(get(urlPathEqualTo(packagingPath())).willReturn(okJson(
                "{\"grossWeightGrams\":180,\"lengthCm\":22,\"widthCm\":12,\"heightCm\":6}")));
    }

    private String packagingPath() {
        return "/inventory/stock/" + productId + "/packaging";
    }

    private ResultActions retry(UUID orderId) throws Exception {
        return mockMvc.perform(post("/delivery/orders/" + orderId + "/ttn/retry")
                .headers(TrustedHeaders.asUser("INVENTORY_WORKER")));
    }

    private Shipment reload(Shipment shipment) {
        return repository.findById(shipment.getId()).orElseThrow();
    }
}
