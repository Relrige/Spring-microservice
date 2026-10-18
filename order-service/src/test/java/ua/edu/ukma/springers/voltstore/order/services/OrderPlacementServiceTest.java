package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutProperties;
import ua.edu.ukma.springers.voltstore.order.dto.DeliveryDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderPlacedEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.security.UserRole;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every branch of EP-ORD-02 with the ports mocked. The transaction is replaced by {@code withoutTransaction()}: what
 * matters here is which steps run and what is released; atomicity is tested with PostgreSQL in the integration test.
 */
@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final UUID PHONE = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final AuthenticatedUser CUSTOMER =
            new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, "ivan@example.com");

    @Mock
    private ProductCatalog catalog;

    @Mock
    private StockReservations stock;

    @Mock
    private OrderRepository orders;

    @Mock
    private Outbox outbox;

    private OrderPlacementService service;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementService(catalog, stock, orders, outbox, TransactionOperations.withoutTransaction(),
                new CheckoutProperties(Duration.ofMinutes(15), Set.of("NOVA_POSHTA")),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void placesTheOrder_withCatalogsTitlesAndPrices_andWritesOrderPlaced() {
        catalogKnows(active(PHONE, "Phone", "19999.50"), active(CASE, "Case", "499.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        PlacedOrderResponse response = service.place(CUSTOMER, request(item(PHONE, 2), item(CASE, 1)));

        ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orders).save(saved.capture());
        OrderEntity order = saved.getValue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getCustomerId()).isEqualTo(CUSTOMER.id());
        assertThat(order.getCustomerEmail()).isEqualTo("ivan@example.com");
        assertThat(order.getCurrency()).isEqualTo("UAH");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("40498.00");
        assertThat(order.getPlacedAt()).isEqualTo(NOW);
        assertThat(order.getReservationExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(order.getDeliveryDetails().getCarrier()).isEqualTo("NOVA_POSHTA");
        assertThat(order.getItems()).extracting(OrderItemEntity::getProductTitle, OrderItemEntity::getUnitPriceAtOrder,
                        OrderItemEntity::getQuantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Phone", new BigDecimal("19999.50"), 2),
                        org.assertj.core.groups.Tuple.tuple("Case", new BigDecimal("499.00"), 1));

        ArgumentCaptor<OrderPlacedEvent> event = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().orderId()).isEqualTo(order.getId());
        assertThat(event.getValue().totalAmount()).isEqualByComparingTo("40498.00");

        assertThat(response).isEqualTo(new PlacedOrderResponse(order.getId(), OrderStatus.PENDING_PAYMENT,
                order.getTotalAmount(), "UAH", NOW.plus(Duration.ofMinutes(15))));
    }

    @Test
    void reservesUnderTheNewOrdersId_beforeTheOrderIsStored() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        PlacedOrderResponse response = service.place(CUSTOMER, request(item(PHONE, 3)));

        InOrder steps = inOrder(catalog, stock, orders, outbox);
        steps.verify(catalog).findProducts(List.of(PHONE));
        steps.verify(stock).reserve(response.orderId(), List.of(new ReservationItem(PHONE, 3)));
        steps.verify(orders).save(any());
        steps.verify(outbox).append(any());
    }

    @Test
    void aReservationThatAlreadyExisted_countsAsReserved() {
        // The adapter reports Inventory's 409 (an earlier call for this order won) as Reserved
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        service.place(CUSTOMER, request(item(PHONE, 1)));

        verify(orders).save(any());
        verify(stock, never()).release(any());
    }

    @Test
    void anInactiveOrUnknownProduct_isRefused_beforeAnythingIsReserved() {
        UUID unknown = UUID.randomUUID();
        catalogKnows(active(PHONE, "Phone", "100.00"),
                new CatalogProduct(CASE, "Case", new BigDecimal("1.00"), CatalogProduct.Status.NOT_ACTIVE, null));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1), item(CASE, 1), item(unknown, 1))))
                .isInstanceOfSatisfying(IneligibleItemsException.class, e -> assertThat(e.getIneligibleItems())
                        .containsExactly(new IneligibleItem(CASE, IneligibleItem.Reason.NOT_ACTIVE),
                                new IneligibleItem(unknown, IneligibleItem.Reason.NOT_ACTIVE)));
        verifyNoInteractions(stock, orders, outbox);
    }

    @Test
    void catalogsStockStatusIsNotChecked_theReservationDecides() {
        catalogKnows(new CatalogProduct(PHONE, "Phone", new BigDecimal("100.00"), CatalogProduct.Status.ACTIVE,
                CatalogProduct.StockStatus.OUT_OF_STOCK));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        service.place(CUSTOMER, request(item(PHONE, 1)));

        verify(orders).save(any());
    }

    @Test
    void insufficientStock_isRefused_withInventorysList_andNoOrder() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        List<InsufficientItem> shortItems = List.of(new InsufficientItem(PHONE, 5, 2));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Insufficient(shortItems));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 5))))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        e -> assertThat(e.getInsufficientItems()).isEqualTo(shortItems));
        verifyNoInteractions(orders, outbox);
        // Nothing was reserved, so there is nothing to release
        verify(stock, never()).release(any());
    }

    @Test
    void catalogUnavailable_createsNothing() {
        when(catalog.findProducts(anyList())).thenThrow(new DependencyUnavailableException("Catalog", "down", null));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1))))
                .isInstanceOf(DependencyUnavailableException.class);
        verifyNoInteractions(stock, orders, outbox);
    }

    @Test
    void inventoryUnavailable_releasesTheReservationItMayHaveMade_andCreatesNothing() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenThrow(new DependencyUnavailableException("Inventory", "timeout", null));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1))))
                .isInstanceOf(DependencyUnavailableException.class);

        ArgumentCaptor<UUID> reservedFor = ArgumentCaptor.forClass(UUID.class);
        verify(stock).reserve(reservedFor.capture(), anyList());
        verify(stock).release(reservedFor.getValue());
        verifyNoInteractions(orders, outbox);
    }

    @Test
    void aFailedSave_releasesTheReservation() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database down");
        doThrow(failure).when(orders).save(any());

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1)))).isSameAs(failure);

        ArgumentCaptor<UUID> reservedFor = ArgumentCaptor.forClass(UUID.class);
        verify(stock).reserve(reservedFor.capture(), anyList());
        verify(stock).release(reservedFor.getValue());
        verifyNoInteractions(outbox);
    }

    @Test
    void aFailedRelease_doesNotHideTheOriginalFailure() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database down");
        doThrow(failure).when(orders).save(any());
        doThrow(new DependencyUnavailableException("Inventory", "down", null)).when(stock).release(any());

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1)))).isSameAs(failure);
    }

    @Test
    void noEmailFromTheGateway_isRefused_beforeAnyCall() {
        AuthenticatedUser withoutEmail = new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, null);

        assertThatThrownBy(() -> service.place(withoutEmail, request(item(PHONE, 1))))
                .isInstanceOf(MissingCustomerEmailException.class);
        verifyNoInteractions(catalog, stock, orders, outbox);
    }

    private void catalogKnows(CatalogProduct... products) {
        when(catalog.findProducts(anyList())).thenReturn(List.of(products));
    }

    private static CatalogProduct active(UUID id, String title, String price) {
        return new CatalogProduct(id, title, new BigDecimal(price), CatalogProduct.Status.ACTIVE,
                CatalogProduct.StockStatus.IN_STOCK);
    }

    private static OrderItemRequest item(UUID productId, int quantity) {
        return new OrderItemRequest(productId, quantity);
    }

    private static PlaceOrderRequest request(OrderItemRequest... items) {
        return new PlaceOrderRequest(List.of(items),
                new DeliveryDetailsRequest("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"));
    }
}
