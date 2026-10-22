package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockDepletedEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockReplenishedEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.CatalogUnavailableException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ProductNotFoundInCatalogException;
import ua.edu.ukma.springers.voltstore.inventory.idempotency.ProcessedEvents;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService.ProductCreatedResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @Mock
    private ProductCatalog productCatalog;

    @Mock
    private ProcessedEvents processedEvents;

    // A mock transaction manager lets TransactionTemplate run the callback (no real transaction in a unit test)
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private StockService stockService;

    @BeforeEach
    void stockItemExistsByDefault() {
        // Most tests work on an existing StockItem, so Catalog is not involved
        lenient().when(stockItemRepository.existsById(PRODUCT_ID)).thenReturn(true);
    }

    @Test
    void listStock_noFilter_passesPageableAndReturnsPage() {
        Pageable pageable = PageRequest.of(1, 5);
        Page<StockItemEntity> expected = new PageImpl<>(List.of(stockItem(1)), pageable, 6);
        when(stockItemRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(expected);

        Page<StockItemEntity> result = stockService.listStock(null, pageable);

        assertThat(result).isSameAs(expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void listStock_withPackagingFilter_queriesRepositoryWithSpecification(boolean configured) {
        Pageable pageable = PageRequest.of(0, 20);
        when(stockItemRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty(pageable));

        stockService.listStock(configured, pageable);

        verify(stockItemRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void adjustStock_restock_increasesAvailableQuantity() {
        StockItemEntity item = stockItem(5);
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item));

        int result = stockService.adjustStock(PRODUCT_ID, 3);

        assertThat(result).isEqualTo(8);
        assertThat(item.getAvailableQuantity()).isEqualTo(8);
    }

    @Test
    void adjustStock_writeOff_decreasesAvailableQuantity() {
        StockItemEntity item = stockItem(5);
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item));

        int result = stockService.adjustStock(PRODUCT_ID, -5);

        assertThat(result).isZero();
        assertThat(item.getAvailableQuantity()).isZero();
    }

    @Test
    void adjustStock_writeOffBelowZero_throwsAndLeavesQuantityUnchanged() {
        StockItemEntity item = stockItem(2);
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> stockService.adjustStock(PRODUCT_ID, -3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Insufficient available stock for write-off");

        assertThat(item.getAvailableQuantity()).isEqualTo(2);
    }

    @Test
    void adjustStock_missingStockItem_productInCatalog_isCreatedOnDemand_afterAskingCatalogOutsideTheTransaction() {
        when(stockItemRepository.existsById(PRODUCT_ID)).thenReturn(false);
        when(productCatalog.find(PRODUCT_ID)).thenReturn(Optional.of(new CatalogProductData(PRODUCT_ID, null, null, null)));
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(0)));

        int result = stockService.adjustStock(PRODUCT_ID, 4);

        assertThat(result).isEqualTo(4);
        // Catalog is asked before the transaction starts, so no lock is held while waiting for it
        InOrder order = inOrder(productCatalog, transactionManager, stockItemRepository);
        order.verify(productCatalog).find(PRODUCT_ID);
        order.verify(transactionManager).getTransaction(any());
        order.verify(stockItemRepository).insertIfAbsent(PRODUCT_ID);
    }

    @Test
    void adjustStock_missingStockItem_catalogAnswerWithVersion_createsTheRowWithTheCopy() {
        when(stockItemRepository.existsById(PRODUCT_ID)).thenReturn(false);
        when(productCatalog.find(PRODUCT_ID)).thenReturn(
                Optional.of(new CatalogProductData(PRODUCT_ID, "Laptop", "NOT_ACTIVE", 3L)));
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(0)));

        stockService.adjustStock(PRODUCT_ID, 4);

        verify(stockItemRepository).upsertProductData(PRODUCT_ID, "Laptop", "NOT_ACTIVE", 3L);
        verify(stockItemRepository, never()).insertIfAbsent(any());
    }

    @Test
    void adjustStock_missingStockItem_catalogAnswerWithUnknownStatus_createsTheRowWithoutTheCopy() {
        when(stockItemRepository.existsById(PRODUCT_ID)).thenReturn(false);
        when(productCatalog.find(PRODUCT_ID)).thenReturn(
                Optional.of(new CatalogProductData(PRODUCT_ID, "Laptop", "ARCHIVED", 3L)));
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(0)));

        stockService.adjustStock(PRODUCT_ID, 4);

        verify(stockItemRepository).insertIfAbsent(PRODUCT_ID);
        verify(stockItemRepository, never()).upsertProductData(any(), any(), any(), anyLong());
    }

    @Test
    void adjustStock_missingStockItem_productNotInCatalog_throwsNotFoundAndCreatesNothing() {
        when(stockItemRepository.existsById(PRODUCT_ID)).thenReturn(false);
        when(productCatalog.find(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.adjustStock(PRODUCT_ID, 4))
                .isInstanceOf(ProductNotFoundInCatalogException.class)
                .hasMessage("Product " + PRODUCT_ID + " not found in Catalog");

        verify(stockItemRepository, never()).insertIfAbsent(any());
        verifyNoInteractions(transactionManager, stockEventPublisher);
    }

    @Test
    void adjustStock_missingStockItem_catalogUnavailable_propagatesAndChangesNothing() {
        when(stockItemRepository.existsById(PRODUCT_ID)).thenReturn(false);
        when(productCatalog.find(PRODUCT_ID))
                .thenThrow(new CatalogUnavailableException("Catalog answered 500", null));

        assertThatThrownBy(() -> stockService.adjustStock(PRODUCT_ID, 4))
                .isInstanceOf(CatalogUnavailableException.class);

        verify(stockItemRepository, never()).insertIfAbsent(any());
        verifyNoInteractions(transactionManager);
    }

    @Test
    void adjustStock_existingStockItem_neverCallsCatalog() {
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(2)));

        stockService.adjustStock(PRODUCT_ID, 1);

        verifyNoInteractions(productCatalog);
    }

    @Test
    void adjustStock_restockFromZero_publishesReplenished() {
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(0)));

        stockService.adjustStock(PRODUCT_ID, 5);

        ArgumentCaptor<StockEvent> captor = ArgumentCaptor.forClass(StockEvent.class);
        verify(stockEventPublisher).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ProductStockReplenishedEvent.class);
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    void adjustStock_writeOffToZero_publishesDepleted() {
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(5)));

        stockService.adjustStock(PRODUCT_ID, -5);

        ArgumentCaptor<StockEvent> captor = ArgumentCaptor.forClass(StockEvent.class);
        verify(stockEventPublisher).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ProductStockDepletedEvent.class);
    }

    @Test
    void adjustStock_positiveToPositive_publishesNothing() {
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(5)));

        stockService.adjustStock(PRODUCT_ID, 3);

        verify(stockEventPublisher, never()).publish(any());
    }

    @Test
    void adjustStock_rejectedWriteOff_publishesNothing() {
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(stockItem(2)));

        assertThatThrownBy(() -> stockService.adjustStock(PRODUCT_ID, -3))
                .isInstanceOf(InsufficientStockException.class);

        verify(stockEventPublisher, never()).publish(any());
    }

    @Test
    void ensureStockItem_absent_createsAndPublishesNothing() {
        when(stockItemRepository.insertIfAbsent(PRODUCT_ID)).thenReturn(1);

        assertThat(stockService.ensureStockItem(PRODUCT_ID)).isTrue();

        verify(stockEventPublisher, never()).publish(any());
    }

    @Test
    void ensureStockItem_alreadyExists_reportsNotCreated() {
        when(stockItemRepository.insertIfAbsent(PRODUCT_ID)).thenReturn(0);

        assertThat(stockService.ensureStockItem(PRODUCT_ID)).isFalse();

        verify(stockEventPublisher, never()).publish(any());
    }

    // --- product events (task 22) ---

    @Test
    void applyProductCreated_fullState_recordsTheKeyAndUpsertsTheCopy() {
        ProductEvent event = event("Laptop", "NOT_ACTIVE", 0L);
        when(processedEvents.markProcessed(event.eventId(), StockService.PRODUCT_CREATED_CONSUMER)).thenReturn(true);
        when(stockItemRepository.upsertProductData(PRODUCT_ID, "Laptop", "NOT_ACTIVE", 0L)).thenReturn(1);

        assertThat(stockService.applyProductCreated(event)).isEqualTo(ProductCreatedResult.APPLIED);

        verify(stockItemRepository, never()).insertIfAbsent(any());
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void applyProductCreated_olderThanTheStoredCopy_isUnchanged() {
        ProductEvent event = event("Laptop", "NOT_ACTIVE", 0L);
        when(processedEvents.markProcessed(event.eventId(), StockService.PRODUCT_CREATED_CONSUMER)).thenReturn(true);
        when(stockItemRepository.upsertProductData(PRODUCT_ID, "Laptop", "NOT_ACTIVE", 0L)).thenReturn(0);

        assertThat(stockService.applyProductCreated(event)).isEqualTo(ProductCreatedResult.UNCHANGED);
    }

    @Test
    void applyProductCreated_withoutVersion_onlyCreatesTheRow() {
        ProductEvent event = event(null, null, null);
        when(processedEvents.markProcessed(event.eventId(), StockService.PRODUCT_CREATED_CONSUMER)).thenReturn(true);
        when(stockItemRepository.insertIfAbsent(PRODUCT_ID)).thenReturn(1);

        assertThat(stockService.applyProductCreated(event)).isEqualTo(ProductCreatedResult.APPLIED);

        verify(stockItemRepository, never()).upsertProductData(any(), any(), any(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVED", "active"})
    void applyProductCreated_unknownStatus_onlyCreatesTheRow(String status) {
        ProductEvent event = event("Laptop", status, 2L);
        when(processedEvents.markProcessed(event.eventId(), StockService.PRODUCT_CREATED_CONSUMER)).thenReturn(true);

        stockService.applyProductCreated(event);

        verify(stockItemRepository).insertIfAbsent(PRODUCT_ID);
        verify(stockItemRepository, never()).upsertProductData(any(), any(), any(), anyLong());
    }

    @Test
    void applyProductCreated_versionWithoutTitle_onlyCreatesTheRow() {
        ProductEvent event = event(null, "ACTIVE", 2L);
        when(processedEvents.markProcessed(event.eventId(), StockService.PRODUCT_CREATED_CONSUMER)).thenReturn(true);

        stockService.applyProductCreated(event);

        verify(stockItemRepository).insertIfAbsent(PRODUCT_ID);
        verify(stockItemRepository, never()).upsertProductData(any(), any(), any(), anyLong());
    }

    @Test
    void applyProductCreated_duplicate_changesNothing() {
        ProductEvent event = event("Laptop", "NOT_ACTIVE", 0L);
        when(processedEvents.markProcessed(event.eventId(), StockService.PRODUCT_CREATED_CONSUMER)).thenReturn(false);

        assertThat(stockService.applyProductCreated(event)).isEqualTo(ProductCreatedResult.DUPLICATE_EVENT);

        verify(stockItemRepository, never()).insertIfAbsent(any());
        verify(stockItemRepository, never()).upsertProductData(any(), any(), any(), anyLong());
    }

    @Test
    void applyProductUpdated_upsertsWithoutAnIdempotencyKey() {
        when(stockItemRepository.upsertProductData(PRODUCT_ID, "Gaming laptop", "ACTIVE", 4L)).thenReturn(1);

        assertThat(stockService.applyProductUpdated(event("Gaming laptop", "ACTIVE", 4L))).isTrue();

        verifyNoInteractions(processedEvents, stockEventPublisher);
    }

    @Test
    void applyProductUpdated_unknownStatus_createsAMissingRowWithoutTheCopy() {
        when(stockItemRepository.insertIfAbsent(PRODUCT_ID)).thenReturn(1);

        assertThat(stockService.applyProductUpdated(event("Laptop", "ARCHIVED", 4L))).isTrue();

        verify(stockItemRepository, never()).upsertProductData(any(), anyString(), anyString(), anyLong());
    }

    private static ProductEvent event(String title, String status, Long version) {
        return new ProductEvent(UUID.randomUUID(), PRODUCT_ID, title, status, version, null);
    }

    private StockItemEntity stockItem(int available) {
        return StockItemEntity.builder().productId(PRODUCT_ID).availableQuantity(available).build();
    }
}
