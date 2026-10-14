package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockDepletedEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    // Fixed ids so the sorted order is known: A < B < C
    private static final UUID PRODUCT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PRODUCT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PRODUCT_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID ORDER_ID = UUID.randomUUID();

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private StockItemReservationRepository reservationRepository;

    @Mock
    private StockEventPublisher stockEventPublisher;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    void reserve_enoughStock_movesQuantitiesAndCreatesOneActiveRowPerItem() {
        StockItemEntity a = item(PRODUCT_A, 10, 1);
        StockItemEntity b = item(PRODUCT_B, 5, 0);
        givenLocked(a, b);

        reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 3, PRODUCT_B, 2));

        assertThat(a.getAvailableQuantity()).isEqualTo(7);
        assertThat(a.getReservedQuantity()).isEqualTo(4);
        assertThat(b.getAvailableQuantity()).isEqualTo(3);
        assertThat(b.getReservedQuantity()).isEqualTo(2);
        assertThat(savedReservations())
                .extracting(StockItemReservationEntity::getOrderId, StockItemReservationEntity::getProductId,
                        StockItemReservationEntity::getQuantity, StockItemReservationEntity::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ORDER_ID, PRODUCT_A, 3, ReservationStatus.ACTIVE),
                        org.assertj.core.groups.Tuple.tuple(ORDER_ID, PRODUCT_B, 2, ReservationStatus.ACTIVE));
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void reserve_locksRowsBeforeCheckingForExistingReservation() {
        givenLocked(item(PRODUCT_A, 10, 0));

        reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 1));

        InOrder order = inOrder(stockItemRepository, reservationRepository);
        order.verify(stockItemRepository).findAllByIdsForUpdate(any());
        order.verify(reservationRepository).existsByOrderIdAndStatus(ORDER_ID, ReservationStatus.ACTIVE);
        order.verify(reservationRepository).saveAll(anyList());
    }

    @Test
    void reserve_partialShortage_listsAllShortItemsAndChangesNothing() {
        StockItemEntity a = item(PRODUCT_A, 1, 0);
        StockItemEntity b = item(PRODUCT_B, 10, 0);
        StockItemEntity c = item(PRODUCT_C, 2, 0);
        givenLocked(a, b, c);

        assertThatThrownBy(() -> reservationService.reserve(ORDER_ID, Map.of(PRODUCT_C, 5, PRODUCT_B, 3, PRODUCT_A, 4)))
                .isInstanceOf(InsufficientStockForReservationException.class)
                .extracting(e -> ((InsufficientStockForReservationException) e).getInsufficientItems())
                .isEqualTo(List.of(new InsufficientItem(PRODUCT_A, 4, 1), new InsufficientItem(PRODUCT_C, 5, 2)));

        assertThat(List.of(a.getAvailableQuantity(), b.getAvailableQuantity(), c.getAvailableQuantity()))
                .containsExactly(1, 10, 2);
        assertThat(List.of(a.getReservedQuantity(), b.getReservedQuantity(), c.getReservedQuantity()))
                .containsOnly(0);
        verify(reservationRepository, never()).saveAll(anyList());
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void reserve_missingStockItem_isShortWithZeroAvailableAndCreatesNothing() {
        StockItemEntity a = item(PRODUCT_A, 10, 0);
        givenLocked(a);

        assertThatThrownBy(() -> reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 1, PRODUCT_B, 2)))
                .isInstanceOf(InsufficientStockForReservationException.class)
                .extracting(e -> ((InsufficientStockForReservationException) e).getInsufficientItems())
                .isEqualTo(List.of(new InsufficientItem(PRODUCT_B, 2, 0)));

        assertThat(a.getAvailableQuantity()).isEqualTo(10);
        verify(stockItemRepository, never()).insertIfAbsent(any());
        verify(reservationRepository, never()).saveAll(anyList());
    }

    @Test
    void reserve_orderAlreadyHasActiveReservation_throwsConflictAndChangesNothing() {
        StockItemEntity a = item(PRODUCT_A, 10, 0);
        givenLocked(a);
        when(reservationRepository.existsByOrderIdAndStatus(ORDER_ID, ReservationStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 1)))
                .isInstanceOf(ReservationAlreadyExistsException.class);

        assertThat(a.getAvailableQuantity()).isEqualTo(10);
        assertThat(a.getReservedQuantity()).isZero();
        verify(reservationRepository, never()).saveAll(anyList());
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void reserve_orderAlreadyReserved_isReportedEvenIfStockIsNowShort() {
        givenLocked(item(PRODUCT_A, 0, 1));
        when(reservationRepository.existsByOrderIdAndStatus(ORDER_ID, ReservationStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 1)))
                .isInstanceOf(ReservationAlreadyExistsException.class);
    }

    @Test
    void reserve_lastUnits_publishesDepletedOnlyForItemThatReachesZero() {
        givenLocked(item(PRODUCT_A, 3, 0), item(PRODUCT_B, 10, 0));

        reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 3, PRODUCT_B, 4));

        ArgumentCaptor<StockEvent> events = ArgumentCaptor.forClass(StockEvent.class);
        verify(stockEventPublisher).publish(events.capture());
        assertThat(events.getAllValues()).singleElement()
                .isInstanceOf(ProductStockDepletedEvent.class)
                .extracting(StockEvent::productId).isEqualTo(PRODUCT_A);
    }

    @Test
    void reserve_severalItemsReachZero_publishesOneDepletedEach() {
        givenLocked(item(PRODUCT_A, 1, 0), item(PRODUCT_B, 2, 0));

        reservationService.reserve(ORDER_ID, Map.of(PRODUCT_A, 1, PRODUCT_B, 2));

        ArgumentCaptor<StockEvent> events = ArgumentCaptor.forClass(StockEvent.class);
        verify(stockEventPublisher, org.mockito.Mockito.times(2)).publish(events.capture());
        assertThat(events.getAllValues()).allMatch(ProductStockDepletedEvent.class::isInstance)
                .extracting(StockEvent::productId).containsExactly(PRODUCT_A, PRODUCT_B);
    }

    private void givenLocked(StockItemEntity... items) {
        when(stockItemRepository.findAllByIdsForUpdate(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return java.util.Arrays.stream(items).filter(i -> ids.contains(i.getProductId())).toList();
        });
    }

    @SuppressWarnings("unchecked")
    private List<StockItemReservationEntity> savedReservations() {
        ArgumentCaptor<List<StockItemReservationEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static StockItemEntity item(UUID productId, int available, int reserved) {
        return StockItemEntity.builder().productId(productId)
                .availableQuantity(available).reservedQuantity(reserved).build();
    }
}
