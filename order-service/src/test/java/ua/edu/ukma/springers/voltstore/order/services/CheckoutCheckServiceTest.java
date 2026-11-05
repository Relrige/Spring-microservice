package ua.edu.ukma.springers.voltstore.order.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct.Status;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct.StockStatus;
import ua.edu.ukma.springers.voltstore.order.ports.ProductCatalog;
import ua.edu.ukma.springers.voltstore.order.ports.StockLevels;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem.Reason.INSUFFICIENT_STOCK;
import static ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem.Reason.NOT_ACTIVE;

@ExtendWith(MockitoExtension.class)
class CheckoutCheckServiceTest {
    private static final UUID PHONE = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final UUID CABLE = UUID.randomUUID();

    @Mock
    private ProductCatalog catalog;

    @Mock
    private StockLevels stock;

    @InjectMocks
    private CheckoutCheckService service;

    @BeforeEach
    void plentyInInventory() {
        // Unless a test says otherwise, Inventory has 99 of every product it is asked about
        lenient().when(stock.availableQuantities(any())).thenAnswer(call -> {
            Map<UUID, Integer> available = new LinkedHashMap<>();
            for (UUID id : call.<java.util.Collection<UUID>>getArgument(0)) {
                available.put(id, 99);
            }
            return available;
        });
    }

    @Test
    void aQuantityAboveInventorysAvailableQuantity_hasInsufficientStock() {
        when(catalog.findProducts(List.of(PHONE, CASE))).thenReturn(List.of(
                product(PHONE, Status.ACTIVE, StockStatus.IN_STOCK),
                product(CASE, Status.ACTIVE, StockStatus.IN_STOCK)));
        when(stock.availableQuantities(List.of(PHONE, CASE))).thenReturn(Map.of(PHONE, 1, CASE, 5));

        assertThat(ineligible(List.of(new OrderItemRequest(PHONE, 2), new OrderItemRequest(CASE, 5))))
                .containsExactly(new IneligibleItem(PHONE, INSUFFICIENT_STOCK));
    }

    @Test
    void inventoryIsAskedOnlyAboutProductsCatalogLetThrough() {
        when(catalog.findProducts(List.of(PHONE, CASE))).thenReturn(List.of(
                product(PHONE, Status.ACTIVE, StockStatus.IN_STOCK),
                product(CASE, Status.NOT_ACTIVE, StockStatus.IN_STOCK)));

        assertThat(ineligible(items(PHONE, CASE))).containsExactly(new IneligibleItem(CASE, NOT_ACTIVE));
        verify(stock).availableQuantities(List.of(PHONE));
    }

    @Test
    void inventoryUnavailable_leavesCatalogsAnswer() {
        when(catalog.findProducts(List.of(PHONE))).thenReturn(List.of(
                product(PHONE, Status.ACTIVE, StockStatus.IN_STOCK)));
        doThrow(new DependencyUnavailableException("Inventory", "down", null)).when(stock).availableQuantities(any());

        assertThatCode(() -> service.check(items(PHONE))).doesNotThrowAnyException();
    }

    @Test
    void everyProductActiveAndInStock_passes_withOneCatalogCall() {
        when(catalog.findProducts(List.of(PHONE, CASE))).thenReturn(List.of(
                product(PHONE, Status.ACTIVE, StockStatus.IN_STOCK),
                product(CASE, Status.ACTIVE, StockStatus.IN_STOCK)));

        assertThatCode(() -> service.check(items(PHONE, CASE))).doesNotThrowAnyException();
        verify(catalog).findProducts(List.of(PHONE, CASE));
    }

    @Test
    void anInactiveProduct_isNotActive() {
        when(catalog.findProducts(List.of(PHONE))).thenReturn(List.of(
                product(PHONE, Status.NOT_ACTIVE, StockStatus.IN_STOCK)));

        assertThat(ineligible(items(PHONE))).containsExactly(new IneligibleItem(PHONE, NOT_ACTIVE));
    }

    @Test
    void aProductCatalogDoesNotKnow_isNotActive() {
        when(catalog.findProducts(List.of(PHONE))).thenReturn(List.of());

        assertThat(ineligible(items(PHONE))).containsExactly(new IneligibleItem(PHONE, NOT_ACTIVE));
    }

    @Test
    void anOutOfStockProduct_hasInsufficientStock() {
        when(catalog.findProducts(List.of(PHONE))).thenReturn(List.of(
                product(PHONE, Status.ACTIVE, StockStatus.OUT_OF_STOCK)));

        assertThat(ineligible(items(PHONE))).containsExactly(new IneligibleItem(PHONE, INSUFFICIENT_STOCK));
    }

    @Test
    void mixedCart_listsEveryIneligibleProduct_inRequestOrder_eachOnce() {
        UUID both = UUID.randomUUID();
        when(catalog.findProducts(List.of(PHONE, CASE, CABLE, both))).thenReturn(List.of(
                product(CABLE, Status.ACTIVE, StockStatus.OUT_OF_STOCK),
                product(PHONE, Status.ACTIVE, StockStatus.IN_STOCK),
                product(both, Status.NOT_ACTIVE, StockStatus.OUT_OF_STOCK)));

        assertThat(ineligible(items(PHONE, CASE, CABLE, both))).containsExactly(
                new IneligibleItem(CASE, NOT_ACTIVE),
                new IneligibleItem(CABLE, INSUFFICIENT_STOCK),
                new IneligibleItem(both, NOT_ACTIVE));
    }

    @Test
    void anUnknownStockStatus_doesNotRefuse() {
        // Catalog before task 24 returns no stockStatus; the reservation decides then
        when(catalog.findProducts(List.of(PHONE))).thenReturn(List.of(product(PHONE, Status.ACTIVE, null)));

        assertThatCode(() -> service.check(items(PHONE))).doesNotThrowAnyException();
    }

    @Test
    void catalogUnavailable_isNeverReportedAsFine() {
        when(catalog.findProducts(List.of(PHONE)))
                .thenThrow(new DependencyUnavailableException("Catalog", "down", null));

        assertThatThrownBy(() -> service.check(items(PHONE))).isInstanceOf(DependencyUnavailableException.class);
    }

    private List<IneligibleItem> ineligible(List<OrderItemRequest> items) {
        try {
            service.check(items);
        } catch (IneligibleItemsException e) {
            return e.getIneligibleItems();
        }
        throw new AssertionError("expected IneligibleItemsException");
    }

    private static List<OrderItemRequest> items(UUID... productIds) {
        return java.util.Arrays.stream(productIds).map(id -> new OrderItemRequest(id, 1)).toList();
    }

    private static CatalogProduct product(UUID id, Status status, StockStatus stockStatus) {
        return new CatalogProduct(id, "Product", new BigDecimal("100.00"), status, stockStatus);
    }
}
