package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.ProductStockReplenishedEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvent;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceReleaseTest {
    private static final UUID PRODUCT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PRODUCT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
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
    void release_singleItem_restoresStockAndMarksReleased() {
        StockItemEntity a = item(PRODUCT_A, 4, 3);
        StockItemReservationEntity reservation = reservation(PRODUCT_A, 3);
        givenActive(reservation);
        givenLocked(a);

        reservationService.release(ORDER_ID);

        assertThat(a.getAvailableQuantity()).isEqualTo(7);
        assertThat(a.getReservedQuantity()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void release_multipleItems_releasesAllOfThem() {
        StockItemEntity a = item(PRODUCT_A, 1, 5);
        StockItemEntity b = item(PRODUCT_B, 10, 2);
        StockItemReservationEntity ra = reservation(PRODUCT_A, 2);
        StockItemReservationEntity rb = reservation(PRODUCT_B, 2);
        givenActive(ra, rb);
        givenLocked(a, b);

        reservationService.release(ORDER_ID);

        assertThat(a.getAvailableQuantity()).isEqualTo(3);
        assertThat(a.getReservedQuantity()).isEqualTo(3);
        assertThat(b.getAvailableQuantity()).isEqualTo(12);
        assertThat(b.getReservedQuantity()).isZero();
        assertThat(List.of(ra.getStatus(), rb.getStatus())).containsOnly(ReservationStatus.RELEASED);
    }

    @Test
    void release_noActiveReservations_changesNothing() {
        givenActive();

        reservationService.release(ORDER_ID);

        verify(stockItemRepository, never()).findAllByIdsForUpdate(any());
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void release_repeated_restoresStockOnce() {
        StockItemEntity a = item(PRODUCT_A, 0, 2);
        StockItemReservationEntity reservation = reservation(PRODUCT_A, 2);
        // The first call sees the ACTIVE row; the second call's query no longer matches it
        when(reservationRepository.findByOrderIdAndStatusForUpdate(ORDER_ID, ReservationStatus.ACTIVE))
                .thenReturn(List.of(reservation), List.of());
        givenLocked(a);

        reservationService.release(ORDER_ID);
        reservationService.release(ORDER_ID);

        assertThat(a.getAvailableQuantity()).isEqualTo(2);
        assertThat(a.getReservedQuantity()).isZero();
        verify(stockItemRepository, times(1)).findAllByIdsForUpdate(any());
    }

    @Test
    void release_locksReservationsBeforeStockRows() {
        givenActive(reservation(PRODUCT_A, 1));
        givenLocked(item(PRODUCT_A, 0, 1));

        reservationService.release(ORDER_ID);

        InOrder order = inOrder(reservationRepository, stockItemRepository);
        order.verify(reservationRepository).findByOrderIdAndStatusForUpdate(ORDER_ID, ReservationStatus.ACTIVE);
        order.verify(stockItemRepository).findAllByIdsForUpdate(List.of(PRODUCT_A));
    }

    @Test
    void release_fromZero_publishesReplenishedOnlyForThatItem() {
        givenActive(reservation(PRODUCT_A, 2), reservation(PRODUCT_B, 1));
        givenLocked(item(PRODUCT_A, 0, 2), item(PRODUCT_B, 5, 1));

        reservationService.release(ORDER_ID);

        ArgumentCaptor<StockEvent> events = ArgumentCaptor.forClass(StockEvent.class);
        verify(stockEventPublisher).publish(events.capture());
        assertThat(events.getAllValues()).singleElement()
                .isInstanceOf(ProductStockReplenishedEvent.class)
                .extracting(StockEvent::productId).isEqualTo(PRODUCT_A);
    }

    private void givenActive(StockItemReservationEntity... reservations) {
        when(reservationRepository.findByOrderIdAndStatusForUpdate(ORDER_ID, ReservationStatus.ACTIVE))
                .thenReturn(List.of(reservations));
    }

    private void givenLocked(StockItemEntity... items) {
        when(stockItemRepository.findAllByIdsForUpdate(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return Arrays.stream(items).filter(i -> ids.contains(i.getProductId())).toList();
        });
    }

    private static StockItemReservationEntity reservation(UUID productId, int quantity) {
        return StockItemReservationEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).productId(productId).quantity(quantity).build();
    }

    private static StockItemEntity item(UUID productId, int available, int reserved) {
        return StockItemEntity.builder().productId(productId)
                .availableQuantity(available).reservedQuantity(reserved).build();
    }
}
