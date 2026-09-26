package ua.edu.ukma.springers.voltstore.inventory.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.inventory.domain.entity.StockItem;
import ua.edu.ukma.springers.voltstore.inventory.domain.entity.StockReservation;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockReservationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryService {
    private final StockItemRepository stockItemRepository;
    private final StockReservationRepository reservationRepository;

    public List<StockItem> getAllStockItems() {
        return stockItemRepository.findAll();
    }

    public StockItem getStockItemBySku(String sku) {
        return stockItemRepository.findBySku(sku)
                .orElseThrow(() -> new RuntimeException("Stock item not found for SKU: " + sku));
    }

    @Transactional
    public StockItem addStock(String sku, Integer quantity) {
        StockItem item = stockItemRepository.findBySku(sku)
                .orElseGet(() -> {
                    StockItem newItem = new StockItem();
                    newItem.setSku(sku);
                    newItem.setAvailableQuantity(0);
                    newItem.setReservedQuantity(0);
                    return newItem;
                });

        item.setAvailableQuantity(item.getAvailableQuantity() + quantity);
        return stockItemRepository.save(item);
    }

    @Transactional
    public StockItem updateItemDetails(String sku, Integer weightGrams, String dimensions) {
        StockItem item = getStockItemBySku(sku);
        item.setWeightGrams(weightGrams);
        item.setDimensions(dimensions);
        return stockItemRepository.save(item);
    }

    @Transactional
    public StockReservation reserveStock(UUID orderId, String sku, Integer quantity) {
        StockItem item = stockItemRepository.findBySku(sku).orElseThrow();
        if (item.getAvailableQuantity() < quantity) {
            throw new RuntimeException("Insufficient stock for SKU: " + sku);
        }

        item.setAvailableQuantity(item.getAvailableQuantity() - quantity);
        item.setReservedQuantity(item.getReservedQuantity() + quantity);
        stockItemRepository.save(item);

        StockReservation reservation = new StockReservation();
        reservation.setOrderId(orderId);
        reservation.setSku(sku);
        reservation.setQuantity(quantity);
        reservation.setStatus(StockReservation.ReservationStatus.ACTIVE);
        reservation.setExpiresAt(LocalDateTime.now().plusMinutes(15));

        return reservationRepository.save(reservation);
    }

    @Transactional
    public void confirmReservation(UUID orderId) {
        reservationRepository.findByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE)
                .forEach(r -> {
                    StockItem item = getStockItemBySku(r.getSku());
                    item.setReservedQuantity(item.getReservedQuantity() - r.getQuantity());
                    r.setStatus(StockReservation.ReservationStatus.COMPLETED);

                    stockItemRepository.save(item);
                    reservationRepository.save(r);
                });
    }

    @Transactional
    public void cancelReservation(UUID orderId) {
        reservationRepository.findByOrderId(orderId).stream()
                .filter(r -> r.getStatus() == StockReservation.ReservationStatus.ACTIVE)
                .forEach(r -> {
                    StockItem item = stockItemRepository.findBySku(r.getSku()).orElseThrow();
                    item.setAvailableQuantity(item.getAvailableQuantity() + r.getQuantity());
                    item.setReservedQuantity(item.getReservedQuantity() - r.getQuantity());

                    r.setStatus(StockReservation.ReservationStatus.CANCELLED);
                    stockItemRepository.save(item);
                    reservationRepository.save(r);
                });
    }
}
