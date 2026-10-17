package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.ShipmentInfo;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryClientAdapterTest extends ClientsTestBase {
    private static final String SHIPMENTS = "/delivery/shipments";
    private static final UUID SHIPPED = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID WAITING = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID FUTURE = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Autowired
    private Shipments shipments;

    @Test
    void find_mapsStatuses_includingNoShipmentYetAndUnknownValues() {
        stubFor(get(urlPathEqualTo(SHIPMENTS)).willReturn(okJson("""
                [ {"orderId":"%s","status":"TTN_GENERATED","ttn":"20450012345678","failureReason":null},
                  {"orderId":"%s","status":null,"ttn":null,"failureReason":null},
                  {"orderId":"%s","status":"LOST_IN_SPACE","ttn":null,"failureReason":null} ]
                """.formatted(SHIPPED, WAITING, FUTURE))));

        List<ShipmentInfo> found = shipments.find(List.of(SHIPPED, WAITING, FUTURE));

        assertThat(found).containsExactly(
                new ShipmentInfo(SHIPPED, DeliveryStatus.TTN_GENERATED, "20450012345678", null),
                new ShipmentInfo(WAITING, null, null, null),
                new ShipmentInfo(FUTURE, null, null, null));
        verify(getRequestedFor(urlPathEqualTo(SHIPMENTS))
                .withQueryParam("orderIds", havingExactly(SHIPPED.toString(), WAITING.toString(), FUTURE.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    void find_moreThan100Orders_isFetchedInChunksOf100() {
        stubFor(get(urlPathEqualTo(SHIPMENTS)).willReturn(okJson("[]")));
        List<UUID> ids = IntStream.range(0, 150).mapToObj(i -> UUID.randomUUID()).toList();

        shipments.find(ids);

        verify(2, getRequestedFor(urlPathEqualTo(SHIPMENTS)));
    }

    @Test
    void find_deliveryDown_isDependencyUnavailable() {
        stubFor(get(urlPathEqualTo(SHIPMENTS)).willReturn(serverError()));

        assertThatThrownBy(() -> shipments.find(List.of(SHIPPED)))
                .isInstanceOfSatisfying(DependencyUnavailableException.class,
                        e -> assertThat(e.getService()).isEqualTo("Delivery"));
    }

    @Test
    void cancel_deletesTheShipment_andIsRetriedAfterA503() {
        String path = SHIPMENTS + "/" + SHIPPED;
        stubFor(delete(urlEqualTo(path)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("up"));
        stubFor(delete(urlEqualTo(path)).inScenario("flaky").whenScenarioStateIs("up").willReturn(noContent()));

        shipments.cancel(SHIPPED);

        verify(2, deleteRequestedFor(urlEqualTo(path)).withHeader("X-Internal-Token", equalTo(TOKEN)));
    }
}
