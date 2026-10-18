package ua.edu.ukma.springers.voltstore.order.services;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @InjectMocks
    private CheckoutCheckService service;

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
