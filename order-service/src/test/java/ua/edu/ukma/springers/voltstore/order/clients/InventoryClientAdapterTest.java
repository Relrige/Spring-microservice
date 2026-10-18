package ua.edu.ukma.springers.voltstore.order.clients;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.Insufficient;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.Reserved;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryClientAdapterTest extends ClientsTestBase {
    private static final String RESERVE = "/inventory/reservations";
    private static final UUID ORDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PHONE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private StockReservations reservations;

    @Test
    void reserve_204_isReserved_andSendsInventorysRequestShape() {
        stubFor(post(urlEqualTo(RESERVE)).willReturn(noContent()));

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 2)))).isEqualTo(new Reserved());

        verify(postRequestedFor(urlEqualTo(RESERVE))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(equalToJson("""
                        {"orderId":"%s","items":[{"productId":"%s","quantity":2}]}""".formatted(ORDER, PHONE))));
    }

    @Test
    void reserve_409_meansAnEarlierCallAlreadyReserved() {
        stubFor(post(urlEqualTo(RESERVE)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json").withBody("{\"status\":409}")));

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 2)))).isEqualTo(new Reserved());
    }

    @Test
    void reserve_422_returnsInventorysListOfShortItems() {
        stubFor(post(urlEqualTo(RESERVE)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/problem+json").withBody("""
                        {"status":422,"insufficientItems":[{"productId":"%s","requested":5,"available":2}]}
                        """.formatted(PHONE))));

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 5))))
                .isEqualTo(new Insufficient(List.of(new InsufficientItem(PHONE, 5, 2))));
    }

    @Test
    void reserve_isRetriedAfterA503() {
        stubFor(post(urlEqualTo(RESERVE)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("up"));
        stubFor(post(urlEqualTo(RESERVE)).inScenario("flaky").whenScenarioStateIs("up").willReturn(noContent()));

        assertThat(reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 1)))).isEqualTo(new Reserved());
        verify(2, postRequestedFor(urlEqualTo(RESERVE)));
    }

    @Test
    void reserve_inventoryDown_isDependencyUnavailable() {
        stubFor(post(urlEqualTo(RESERVE)).willReturn(serverError()));

        assertThatThrownBy(() -> reservations.reserve(ORDER, List.of(new ReservationItem(PHONE, 1))))
                .isInstanceOfSatisfying(DependencyUnavailableException.class,
                        e -> assertThat(e.getService()).isEqualTo("Inventory"));
    }

    @Test
    void release_postsToTheOrdersReleasePath() {
        stubFor(post(urlEqualTo(RESERVE + "/" + ORDER + "/release")).willReturn(noContent()));

        reservations.release(ORDER);

        verify(postRequestedFor(urlEqualTo(RESERVE + "/" + ORDER + "/release"))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    void release_failing_isDependencyUnavailable_afterRetries() {
        stubFor(post(urlEqualTo(RESERVE + "/" + ORDER + "/release")).willReturn(serverError()));

        assertThatThrownBy(() -> reservations.release(ORDER)).isInstanceOf(DependencyUnavailableException.class);
        verify(3, postRequestedFor(urlEqualTo(RESERVE + "/" + ORDER + "/release")));
    }
}
