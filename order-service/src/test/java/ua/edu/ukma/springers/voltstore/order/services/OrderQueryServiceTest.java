package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ua.edu.ukma.springers.voltstore.order.dto.OrderDetailsResponse;
import ua.edu.ukma.springers.voltstore.order.dto.OrderSummaryResponse;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.paging.InvalidQueryParameterException;
import ua.edu.ukma.springers.voltstore.order.paging.PageResponse;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments.DeliveryStatus;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.security.UserRole;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {
    private static final Instant PLACED = Instant.parse("2026-10-17T10:00:00Z");
    private static final AuthenticatedUser CUSTOMER =
            new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, "ivan@example.com");

    @Mock
    private OrderRepository orders;

    @Mock
    private OrderAccess orderAccess;

    @Mock
    private Shipments shipments;

    @InjectMocks
    private OrderQueryService service;

    @Test
    void history_isTheCallersOrders_newestFirst_inTheSharedEnvelope() {
        OrderEntity order = order();
        when(orders.findByCustomerId(any(), any())).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(order), pageable, 41);
        });

        PageResponse<OrderSummaryResponse> page = service.history(CUSTOMER, 2, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orders).findByCustomerId(org.mockito.ArgumentMatchers.eq(CUSTOMER.id()), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id")));
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.content()).containsExactly(OrderSummaryResponse.of(order));
    }

    @Test
    void history_rejectsInvalidPaging() {
        assertThatThrownBy(() -> service.history(CUSTOMER, -1, 20))
                .isInstanceOfSatisfying(InvalidQueryParameterException.class,
                        e -> assertThat(e.getParameter()).isEqualTo("page"));
        assertThatThrownBy(() -> service.history(CUSTOMER, 0, 0)).isInstanceOf(InvalidQueryParameterException.class);
        assertThatThrownBy(() -> service.history(CUSTOMER, 0, 101))
                .isInstanceOfSatisfying(InvalidQueryParameterException.class,
                        e -> assertThat(e.getParameter()).isEqualTo("size"));
        verifyNoInteractions(orders);
    }

    @Test
    void details_ofAnUnpaidOrder_doNotCallDelivery() {
        OrderEntity order = order();
        when(orderAccess.loadOwned(order.getId(), CUSTOMER)).thenReturn(order);

        OrderDetailsResponse details = service.details(order.getId(), CUSTOMER);

        assertThat(details.ttn()).isNull();
        assertThat(details.deliveryStatus()).isNull();
        assertThat(details.items()).hasSize(1);
        verifyNoInteractions(shipments);
    }

    @Test
    void details_ofAPaidOrder_includeTheShipment() {
        OrderEntity order = paidOrder();
        when(orderAccess.loadOwned(order.getId(), CUSTOMER)).thenReturn(order);
        when(shipments.find(List.of(order.getId()))).thenReturn(List.of(
                new Shipments.ShipmentInfo(order.getId(), DeliveryStatus.TTN_GENERATED, "20450012345678", null)));

        OrderDetailsResponse details = service.details(order.getId(), CUSTOMER);

        assertThat(details.ttn()).isEqualTo("20450012345678");
        assertThat(details.deliveryStatus()).isEqualTo(DeliveryStatus.TTN_GENERATED);
        assertThat(details.paidAt()).isEqualTo(PLACED.plusSeconds(60));
        assertThat(details.deliveryDetails().carrier()).isEqualTo("NOVA_POSHTA");
    }

    @Test
    void details_withoutAShipmentYet_haveNoTtn() {
        OrderEntity order = paidOrder();
        when(orderAccess.loadOwned(order.getId(), CUSTOMER)).thenReturn(order);
        when(shipments.find(List.of(order.getId())))
                .thenReturn(List.of(new Shipments.ShipmentInfo(order.getId(), null, null, null)));

        OrderDetailsResponse details = service.details(order.getId(), CUSTOMER);

        assertThat(details.ttn()).isNull();
        assertThat(details.deliveryStatus()).isNull();
    }

    @Test
    void details_withDeliveryDown_stillShowTheOrder() {
        OrderEntity order = paidOrder();
        when(orderAccess.loadOwned(order.getId(), CUSTOMER)).thenReturn(order);
        when(shipments.find(any())).thenThrow(new DependencyUnavailableException("Delivery", "down", null));

        OrderDetailsResponse details = service.details(order.getId(), CUSTOMER);

        assertThat(details.orderId()).isEqualTo(order.getId());
        assertThat(details.ttn()).isNull();
        assertThat(details.deliveryStatus()).isNull();
    }

    @Test
    void details_ofAnOrderCancelledAfterPayment_includeTheShipment() {
        OrderEntity order = paidOrder();
        order.requestCancellation(PLACED);
        when(orderAccess.loadOwned(order.getId(), CUSTOMER)).thenReturn(order);
        when(shipments.find(List.of(order.getId()))).thenReturn(List.of(
                new Shipments.ShipmentInfo(order.getId(), DeliveryStatus.CANCELLED, null, null)));

        assertThat(service.details(order.getId(), CUSTOMER).deliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
    }

    private static OrderEntity paidOrder() {
        OrderEntity order = order();
        order.markPaid(PLACED.plusSeconds(60));
        return order;
    }

    private static OrderEntity order() {
        return OrderEntity.place(UUID.randomUUID(), CUSTOMER.id(), CUSTOMER.email(),
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 2)),
                "UAH", PLACED, Duration.ofMinutes(15));
    }
}
