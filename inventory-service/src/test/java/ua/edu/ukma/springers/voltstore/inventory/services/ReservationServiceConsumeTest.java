package ua.edu.ukma.springers.voltstore.inventory.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// EP-INV-EVT-02: consuming the reservations of a shipped order
@ExtendWith(MockitoExtension.class)
class ReservationServiceConsumeTest {
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
    void consume_multiItemOrder_deductsReservedOnlyAndMarksConsumed() {
        StockItemEntity a = item(PRODUCT_A, 6, 4);
        StockItemEntity b = item(PRODUCT_B, 0, 3);
        StockItemReservationEntity ra = reservation(PRODUCT_A, 4);
        StockItemReservationEntity rb = reservation(PRODUCT_B, 3);
        givenActive(ra, rb);
        givenLocked(a, b);

        assertThat(reservationService.consume(ORDER_ID)).isEqualTo(2);

        assertThat(a.getReservedQuantity()).isZero();
        assertThat(b.getReservedQuantity()).isZero();
        // availableQuantity was already reduced at reservation time
        assertThat(a.getAvailableQuantity()).isEqualTo(6);
        assertThat(b.getAvailableQuantity()).isZero();
        assertThat(List.of(ra.getStatus(), rb.getStatus())).containsOnly(ReservationStatus.CONSUMED);
        // available did not change, so no stock event (not even for b, which stays at 0)
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void consume_locksReservationsBeforeStockRows() {
        givenActive(reservation(PRODUCT_A, 1));
        givenLocked(item(PRODUCT_A, 0, 1));

        reservationService.consume(ORDER_ID);

        InOrder order = inOrder(reservationRepository, stockItemRepository);
        order.verify(reservationRepository).findByOrderIdAndStatusForUpdate(ORDER_ID, ReservationStatus.ACTIVE);
        order.verify(stockItemRepository).findAllByIdsForUpdate(any());
    }

    @Test
    void consume_noActiveReservations_changesNothing() {
        givenActive();

        assertThat(reservationService.consume(ORDER_ID)).isZero();

        verify(stockItemRepository, never()).findAllByIdsForUpdate(any());
        verifyNoInteractions(stockEventPublisher);
    }

    @Test
    void consume_repeated_deductsOnce() {
        StockItemEntity a = item(PRODUCT_A, 1, 2);
        StockItemReservationEntity reservation = reservation(PRODUCT_A, 2);
        // The first call sees the ACTIVE row; the duplicate's query no longer matches it
        when(reservationRepository.findByOrderIdAndStatusForUpdate(ORDER_ID, ReservationStatus.ACTIVE))
                .thenReturn(List.of(reservation), List.of());
        givenLocked(a);

        assertThat(reservationService.consume(ORDER_ID)).isEqualTo(1);
        assertThat(reservationService.consume(ORDER_ID)).isZero();

        assertThat(a.getReservedQuantity()).isZero();
        assertThat(a.getAvailableQuantity()).isEqualTo(1);
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
