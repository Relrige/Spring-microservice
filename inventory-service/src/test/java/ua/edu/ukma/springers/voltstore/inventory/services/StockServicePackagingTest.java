package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServicePackagingTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @InjectMocks
    private StockService stockService;

    @Test
    void setPackaging_replacesAllFourValuesAndKeepsQuantities() {
        StockItemEntity item = StockItemEntity.builder().productId(PRODUCT_ID)
                .availableQuantity(7).reservedQuantity(3).grossWeightGrams(100).lengthCm(1).widthCm(1).heightCm(1).build();
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item));

        stockService.setPackaging(PRODUCT_ID, 500, 10, 5, 2);

        assertThat(item.getGrossWeightGrams()).isEqualTo(500);
        assertThat(item.getLengthCm()).isEqualTo(10);
        assertThat(item.getWidthCm()).isEqualTo(5);
        assertThat(item.getHeightCm()).isEqualTo(2);
        assertThat(item.getAvailableQuantity()).isEqualTo(7);
        assertThat(item.getReservedQuantity()).isEqualTo(3);
        assertThat(item.isPackagingConfigured()).isTrue();
    }

    @Test
    void setPackaging_createsMissingItemBeforeLockingIt() {
        StockItemEntity created = StockItemEntity.builder().productId(PRODUCT_ID).build();
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(created));

        stockService.setPackaging(PRODUCT_ID, 500, 10, 5, 2);

        InOrder order = inOrder(stockItemRepository);
        order.verify(stockItemRepository).insertIfAbsent(PRODUCT_ID);
        order.verify(stockItemRepository).findByIdForUpdate(PRODUCT_ID);
        assertThat(created.getAvailableQuantity()).isZero();
        assertThat(created.getReservedQuantity()).isZero();
        assertThat(created.isPackagingConfigured()).isTrue();
    }

    @Test
    void setPackaging_repeatedWithSameValues_leavesSameState() {
        StockItemEntity item = StockItemEntity.builder().productId(PRODUCT_ID).availableQuantity(4).build();
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(item));

        stockService.setPackaging(PRODUCT_ID, 500, 10, 5, 2);
        stockService.setPackaging(PRODUCT_ID, 500, 10, 5, 2);

        assertThat(item.getGrossWeightGrams()).isEqualTo(500);
        assertThat(item.getLengthCm()).isEqualTo(10);
        assertThat(item.getWidthCm()).isEqualTo(5);
        assertThat(item.getHeightCm()).isEqualTo(2);
        assertThat(item.getAvailableQuantity()).isEqualTo(4);
    }

    @Test
    void setPackaging_emitsNoEvent() {
        when(stockItemRepository.findByIdForUpdate(PRODUCT_ID))
                .thenReturn(Optional.of(StockItemEntity.builder().productId(PRODUCT_ID).build()));

        stockService.setPackaging(PRODUCT_ID, 500, 10, 5, 2);

        verifyNoInteractions(stockEventPublisher);
    }
}
