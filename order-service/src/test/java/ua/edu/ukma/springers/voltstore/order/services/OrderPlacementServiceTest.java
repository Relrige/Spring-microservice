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
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutProperties;
import ua.edu.ukma.springers.voltstore.order.dto.DeliveryDetailsRequest;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.IdempotencyKey;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.OrderPlacedEvent;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IdempotencyKeyMismatchException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.exceptions.RequestInProgressException;
import ua.edu.ukma.springers.voltstore.order.outbox.Outbox;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;
import ua.edu.ukma.springers.voltstore.order.repositories.IdempotencyKeyRepository;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.repositories.ReservationAttemptRepository;
import ua.edu.ukma.springers.voltstore.order.entities.ReservationAttempt;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderTotalTooLargeException;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.security.UserRole;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    @Mock
    private ReservationAttemptRepository attempts;

    @Mock
    private IdempotencyKeyRepository keys;

    private OrderPlacementService service;

    @BeforeEach
    void setUp() {
        IdempotencyKeys idempotencyKeys = new IdempotencyKeys(keys, TransactionOperations.withoutTransaction(),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        service = new OrderPlacementService(catalog, stock, orders, attempts, idempotencyKeys, outbox,
                TransactionOperations.withoutTransaction(),
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

    // ---- the reservation attempt (orphan reservations, decision 2026-10-18) ----

    @Test
    void theAttemptIsWrittenBeforeTheReservation_andDeletedWithTheOrder() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        PlacedOrderResponse response = service.place(CUSTOMER, request(item(PHONE, 1)));

        InOrder steps = inOrder(attempts, stock, orders);
        ArgumentCaptor<ReservationAttempt> attempt = ArgumentCaptor.forClass(ReservationAttempt.class);
        steps.verify(attempts).save(attempt.capture());
        steps.verify(stock).reserve(eq(response.orderId()), anyList());
        steps.verify(orders).save(any());
        steps.verify(attempts).deleteById(response.orderId());
        assertThat(attempt.getValue().getOrderId()).isEqualTo(response.orderId());
        assertThat(attempt.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void insufficientStock_deletesTheAttempt_becauseNothingWasReserved() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList()))
                .thenReturn(new StockReservations.Insufficient(List.of(new InsufficientItem(PHONE, 5, 1))));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 5))))
                .isInstanceOf(InsufficientStockException.class);
        verify(attempts).deleteById(any());
    }

    @Test
    void inventoryUnavailable_keepsTheAttempt_forTheJob() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenThrow(new DependencyUnavailableException("Inventory", "timeout", null));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1))))
                .isInstanceOf(DependencyUnavailableException.class);
        // Released now, and kept: a late reservation is released again by the job
        verify(attempts).save(any());
        verify(attempts, never()).deleteById(any());
    }

    @Test
    void aFailedSave_keepsTheAttempt_forTheJob() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());
        doThrow(new DataAccessResourceFailureException("database down")).when(orders).save(any());

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(attempts, never()).deleteById(any());
    }

    // ---- limits ----

    @Test
    void aTotalPaymentCannotCharge_isRefused_beforeAnythingIsReserved() {
        // 10 × 1,000,000,000.00 = 10,000,000,000.00, one cent more than Payment and the column allow
        catalogKnows(active(PHONE, "Phone", "1000000000.00"));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 10))))
                .isInstanceOfSatisfying(OrderTotalTooLargeException.class,
                        e -> assertThat(e.getMaxTotal()).isEqualByComparingTo("9999999999.99"));
        verifyNoInteractions(stock, attempts, orders, outbox);
    }

    @Test
    void theLargestPayableTotal_isAccepted() {
        catalogKnows(active(PHONE, "Phone", "999999999.99"), active(CASE, "Case", "0.09"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        // 10 × 999,999,999.99 + 0.09 = 9,999,999,999.99, exactly the limit
        assertThat(service.place(CUSTOMER, request(item(PHONE, 10), item(CASE, 1))).totalAmount())
                .isEqualByComparingTo("9999999999.99");
    }

    @Test
    void noEmailFromTheGateway_isRefused_beforeAnyCall() {
        AuthenticatedUser withoutEmail = new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, null);

        assertThatThrownBy(() -> service.place(withoutEmail, request(item(PHONE, 1))))
                .isInstanceOf(MissingCustomerEmailException.class);
        verifyNoInteractions(catalog, stock, orders, outbox);
    }

    // ---- Idempotency-Key (D4-D9) ----

    @Test
    void withoutAKey_noKeyRowIsTouched() {
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        assertThat(service.place(CUSTOMER, request(item(PHONE, 1)), null).replayed()).isFalse();
        verifyNoInteractions(keys);
    }

    @Test
    void theOwner_claimsBeforeCatalog_andCompletesWithTheOrder() {
        UUID key = UUID.randomUUID();
        when(keys.claim(eq(CUSTOMER.id()), eq(key), any(), eq(NOW))).thenReturn(1);
        when(keys.complete(any(), any(), any())).thenReturn(1);
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        OrderPlacementService.Placement placed = service.place(CUSTOMER, request(item(PHONE, 1)), key);

        assertThat(placed.replayed()).isFalse();
        InOrder steps = inOrder(keys, catalog, stock, orders);
        steps.verify(keys).claim(eq(CUSTOMER.id()), eq(key), any(), eq(NOW));
        steps.verify(catalog).findProducts(anyList());
        steps.verify(stock).reserve(any(), anyList());
        steps.verify(orders).save(any());
        steps.verify(keys).complete(CUSTOMER.id(), key, placed.order().orderId());
        verify(keys, never()).release(any(), any());
    }

    @Test
    void aCompletionThatFindsNoClaim_stillStoresTheOrder() {
        UUID key = UUID.randomUUID();
        when(keys.claim(any(), any(), any(), any())).thenReturn(1);
        when(keys.complete(any(), any(), any())).thenReturn(0);
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());

        service.place(CUSTOMER, request(item(PHONE, 1)), key);

        verify(orders).save(any());
        verify(stock, never()).release(any());
    }

    @Test
    void aReplayOfACompletedKey_returnsTheCurrentOrder_withoutCallingAnyone() {
        UUID key = UUID.randomUUID();
        PlaceOrderRequest request = request(item(PHONE, 1));
        OrderEntity stored = storedOrder();
        stored.markPaid(NOW);
        givenExistingKey(key, request, IdempotencyKey.Status.COMPLETED, stored.getId());
        when(orders.findById(stored.getId())).thenReturn(Optional.of(stored));

        OrderPlacementService.Placement placed = service.place(CUSTOMER, request, key);

        assertThat(placed.replayed()).isTrue();
        assertThat(placed.order().orderId()).isEqualTo(stored.getId());
        assertThat(placed.order().status()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(catalog, stock, attempts, outbox);
        verify(orders, never()).save(any());
    }

    @Test
    void aKeyUsedForAnotherRequest_isRefused_whateverItsStatus() {
        UUID key = UUID.randomUUID();
        givenExistingKey(key, request(item(PHONE, 1)), IdempotencyKey.Status.COMPLETED, UUID.randomUUID());

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 2)), key))
                .isInstanceOf(IdempotencyKeyMismatchException.class);
        verifyNoInteractions(catalog, stock, orders, attempts, outbox);
    }

    @Test
    void aKeyAnotherRequestHasClaimed_isAnsweredInProgress() {
        UUID key = UUID.randomUUID();
        PlaceOrderRequest request = request(item(PHONE, 1));
        givenExistingKey(key, request, IdempotencyKey.Status.IN_PROGRESS, null);

        assertThatThrownBy(() -> service.place(CUSTOMER, request, key))
                .isInstanceOf(RequestInProgressException.class);
        verifyNoInteractions(catalog, stock, orders, attempts, outbox);
        verify(keys, never()).release(any(), any());
    }

    @Test
    void ineligibleItems_releaseTheClaim() {
        UUID key = givenClaimedKey();
        catalogKnows(new CatalogProduct(PHONE, "Phone", BigDecimal.ONE, CatalogProduct.Status.NOT_ACTIVE, null));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1)), key))
                .isInstanceOf(IneligibleItemsException.class);
        verify(keys).release(CUSTOMER.id(), key);
    }

    @Test
    void aTotalTooLarge_releasesTheClaim() {
        UUID key = givenClaimedKey();
        catalogKnows(active(PHONE, "Phone", "1000000000.00"));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 10)), key))
                .isInstanceOf(OrderTotalTooLargeException.class);
        verify(keys).release(CUSTOMER.id(), key);
    }

    @Test
    void insufficientStock_releasesTheClaim() {
        UUID key = givenClaimedKey();
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList()))
                .thenReturn(new StockReservations.Insufficient(List.of(new InsufficientItem(PHONE, 5, 1))));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 5)), key))
                .isInstanceOf(InsufficientStockException.class);
        verify(keys).release(CUSTOMER.id(), key);
    }

    @Test
    void anUnavailableDependency_releasesTheClaim_andKeepsTheAttempt() {
        UUID key = givenClaimedKey();
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenThrow(new DependencyUnavailableException("Inventory", "down", null));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1)), key))
                .isInstanceOf(DependencyUnavailableException.class);
        verify(keys).release(CUSTOMER.id(), key);
        verify(attempts, never()).deleteById(any());
    }

    @Test
    void aFailedSave_releasesTheClaim_andTheReservation() {
        UUID key = givenClaimedKey();
        catalogKnows(active(PHONE, "Phone", "100.00"));
        when(stock.reserve(any(), anyList())).thenReturn(new StockReservations.Reserved());
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database down");
        doThrow(failure).when(orders).save(any());

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1)), key)).isSameAs(failure);
        verify(keys).release(CUSTOMER.id(), key);
        verify(stock).release(any());
    }

    @Test
    void aFailedRelease_doesNotHideTheOriginalFailure_ofAKeyedRequest() {
        UUID key = givenClaimedKey();
        DependencyUnavailableException failure = new DependencyUnavailableException("Catalog", "down", null);
        when(catalog.findProducts(anyList())).thenThrow(failure);
        when(keys.release(any(), any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> service.place(CUSTOMER, request(item(PHONE, 1)), key)).isSameAs(failure);
    }

    private UUID givenClaimedKey() {
        when(keys.claim(any(), any(), any(), any())).thenReturn(1);
        return UUID.randomUUID();
    }

    private void givenExistingKey(UUID key, PlaceOrderRequest firstRequest, IdempotencyKey.Status status,
                                  UUID orderId) {
        // The row as the first request left it, with that request's hash
        IdempotencyKeys hasher = new IdempotencyKeys(keys, TransactionOperations.withoutTransaction(),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        IdempotencyKey row = mock(IdempotencyKey.class);
        when(row.getRequestHash()).thenReturn(hasher.hash(firstRequest));
        lenient().when(row.getStatus()).thenReturn(status);
        lenient().when(row.getOrderId()).thenReturn(orderId);
        when(keys.claim(any(), eq(key), any(), any())).thenReturn(0);
        when(keys.findById(new IdempotencyKey.Id(CUSTOMER.id(), key))).thenReturn(Optional.of(row));
    }

    private static OrderEntity storedOrder() {
        return OrderEntity.place(UUID.randomUUID(), CUSTOMER.id(), CUSTOMER.email(),
                new DeliveryDetails("Ivan Petrenko", "+380501234567",
                        "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(PHONE, "Phone", new BigDecimal("100.00"), 1)), "UAH", NOW,
                Duration.ofMinutes(15));
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
