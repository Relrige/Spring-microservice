package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockServiceReadinessTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @InjectMocks
    private StockService stockService;

    @Test
    void checkReadiness_packagingConfigured_succeeds() {
        when(stockItemRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(configuredItem()));

        assertThatCode(() -> stockService.checkReadiness(PRODUCT_ID)).doesNotThrowAnyException();

        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void checkReadiness_missingItem_throwsNotFoundAndCreatesNothing() {
        when(stockItemRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.checkReadiness(PRODUCT_ID))
                .isInstanceOf(StockItemNotFoundException.class);

        verify(stockItemRepository, never()).insertIfAbsent(any());
        verify(stockItemRepository, never()).save(any());
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void checkReadiness_noPackaging_throwsPackagingNotConfigured() {
        when(stockItemRepository.findById(PRODUCT_ID))
                .thenReturn(Optional.of(StockItemEntity.builder().productId(PRODUCT_ID).availableQuantity(5).build()));

        assertThatThrownBy(() -> stockService.checkReadiness(PRODUCT_ID))
                .isInstanceOf(PackagingNotConfiguredException.class);

        verifyNoInteractions(stockEventPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"grossWeightGrams", "lengthCm", "widthCm", "heightCm"})
    void checkReadiness_oneValueNullOrNotPositive_throwsPackagingNotConfigured(String field) {
        for (Integer value : new Integer[]{null, 0, -1}) {
            StockItemEntity item = configuredItem();
            switch (field) {
                case "grossWeightGrams" -> item.setGrossWeightGrams(value);
                case "lengthCm" -> item.setLengthCm(value);
                case "widthCm" -> item.setWidthCm(value);
                default -> item.setHeightCm(value);
            }
            when(stockItemRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> stockService.checkReadiness(PRODUCT_ID))
                    .as("%s = %s", field, value)
                    .isInstanceOf(PackagingNotConfiguredException.class);
        }
    }

    private static StockItemEntity configuredItem() {
        return StockItemEntity.builder().productId(PRODUCT_ID)
                .grossWeightGrams(500).lengthCm(10).widthCm(5).heightCm(2).build();
    }
}
