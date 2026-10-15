package ua.edu.ukma.springers.voltstore.inventory.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.inventory.entities.ReservationStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemReservationEntity;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEventPublisher;
import ua.edu.ukma.springers.voltstore.inventory.events.StockEvents;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemReservationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationService {
    private final StockItemRepository stockItemRepository;
    private final StockItemReservationRepository reservationRepository;
    private final StockEventPublisher stockEventPublisher;

    /**
     * Holds stock for every item of the order, all or nothing: moves each quantity from available to reserved
     * and records one ACTIVE reservation per product. Nothing changes if the order already holds a reservation
     * (409) or any item is short (422, listing all short items).
     *
     * @param quantities requested quantity per product (one entry per product)
     */
    @Transactional
    public void reserve(UUID orderId, Map<UUID, Integer> quantities) {
        // Sorted, so shortages are reported and rows are visited in a stable order
        SortedMap<UUID, Integer> requested = new TreeMap<>(quantities);

        // Lock first (sorted by productId, so two orders cannot deadlock), then check for an existing reservation.
        // A concurrent call for the same order (e.g. a retry after a timeout) has the same items, so it waits
        // on these row locks and then sees the reservations committed by the first call.
        Map<UUID, StockItemEntity> items = stockItemRepository.findAllByIdsForUpdate(requested.keySet()).stream()
                .collect(Collectors.toMap(StockItemEntity::getProductId, Function.identity()));
        if (reservationRepository.existsByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)) {
            throw new ReservationAlreadyExistsException(orderId);
        }

        // Find all shortages before changing anything; a product without a StockItem has nothing available
        List<InsufficientItem> shortages = new ArrayList<>();
        requested.forEach((productId, quantity) -> {
            StockItemEntity item = items.get(productId);
            int available = item == null ? 0 : item.getAvailableQuantity();
            if (available < quantity) {
                shortages.add(new InsufficientItem(productId, quantity, available));
            }
        });
        if (!shortages.isEmpty()) {
            throw new InsufficientStockForReservationException(shortages);
        }

        List<StockItemReservationEntity> reservations = new ArrayList<>();
        requested.forEach((productId, quantity) -> {
            StockItemEntity item = items.get(productId);
            int before = item.getAvailableQuantity();
            int after = before - quantity;
            item.setAvailableQuantity(after);
            item.setReservedQuantity(item.getReservedQuantity() + quantity);
            // Written to the outbox in this transaction; published by the relay after the commit
            StockEvents.forTransition(productId, before, after).ifPresent(stockEventPublisher::publish);
            reservations.add(StockItemReservationEntity.builder()
                    .orderId(orderId).productId(productId).quantity(quantity).build());
        });

        reservationRepository.saveAll(reservations);
    }

    /**
     * Returns the stock held by the order's ACTIVE reservations to available and marks them RELEASED, all at once.
     * Idempotent: an order without ACTIVE reservations (never reserved, already released or shipped) is a no-op.
     */
    @Transactional
    public void release(UUID orderId) {
        // Lock the reservations first. A concurrent release of the same order waits here and, after the first one
        // commits, finds no ACTIVE rows, so stock is restored exactly once.
        List<StockItemReservationEntity> reservations =
                reservationRepository.findByOrderIdAndStatusForUpdate(orderId, ReservationStatus.ACTIVE);
        if (reservations.isEmpty()) {
            return;
        }

        // Then lock the stock rows
        Map<UUID, StockItemEntity> items = stockItemRepository.findAllByIdsForUpdate(reservations.stream()
                        .map(StockItemReservationEntity::getProductId).toList()).stream()
                .collect(Collectors.toMap(StockItemEntity::getProductId, Function.identity()));

        for (StockItemReservationEntity reservation : reservations) {
            // The foreign key guarantees a StockItem for every reservation
            StockItemEntity item = items.get(reservation.getProductId());
            int before = item.getAvailableQuantity();
            int after = before + reservation.getQuantity();
            item.setAvailableQuantity(after);
            item.setReservedQuantity(item.getReservedQuantity() - reservation.getQuantity());
            reservation.setStatus(ReservationStatus.RELEASED);
            // Written to the outbox in this transaction; published by the relay after the commit
            StockEvents.forTransition(reservation.getProductId(), before, after).ifPresent(stockEventPublisher::publish);
        }
    }

    /**
     * EP-INV-EVT-02: the order was shipped, so the stock held by its ACTIVE reservations leaves the warehouse for
     * good: {@code reservedQuantity} goes down and the reservations become CONSUMED. {@code availableQuantity} was
     * already reduced at reservation time and does not change, so no stock event is emitted.
     * <p>
     * Same lock order as {@link #release}: reservation rows first, then stock rows sorted by productId. A concurrent
     * duplicate event (or a release of the same order) waits on the reservation rows and then finds none ACTIVE,
     * so the deduction happens exactly once, and consume and release cannot deadlock each other. RELEASED
     * reservations are never consumed.
     *
     * @return the number of reservations consumed (0: nothing ACTIVE, e.g. a duplicate event)
     */
    @Transactional
    public int consume(UUID orderId) {
        List<StockItemReservationEntity> reservations =
                reservationRepository.findByOrderIdAndStatusForUpdate(orderId, ReservationStatus.ACTIVE);
        if (reservations.isEmpty()) {
            return 0;
        }

        Map<UUID, StockItemEntity> items = stockItemRepository.findAllByIdsForUpdate(reservations.stream()
                        .map(StockItemReservationEntity::getProductId).toList()).stream()
                .collect(Collectors.toMap(StockItemEntity::getProductId, Function.identity()));

        for (StockItemReservationEntity reservation : reservations) {
            // The foreign key guarantees a StockItem for every reservation
            StockItemEntity item = items.get(reservation.getProductId());
            item.setReservedQuantity(item.getReservedQuantity() - reservation.getQuantity());
            reservation.setStatus(ReservationStatus.CONSUMED);
        }
        return reservations.size();
    }
}
