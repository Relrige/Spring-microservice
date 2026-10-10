package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockDepletedEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockReplenishedEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @InjectMocks
    private StockService stockService;

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

    /**
     * TEMPORARY (task 19): the StockItem is created without a Catalog existence check.
     * When the Catalog check arrives, this test changes: an unknown product must not be created.
     */
    @Test
    void adjustStock_missingStockItem_isCreatedOnDemandWithoutCatalogCheck_changesInTask19() {
        StockItemEntity created = stockItem(0);
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(created));

        int result = stockService.adjustStock(PRODUCT_ID, 4);

        verify(stockItemRepository).insertIfAbsent(PRODUCT_ID);
        assertThat(result).isEqualTo(4);
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

    private StockItemEntity stockItem(int available) {
        return StockItemEntity.builder().productId(PRODUCT_ID).availableQuantity(available).build();
    }
}
