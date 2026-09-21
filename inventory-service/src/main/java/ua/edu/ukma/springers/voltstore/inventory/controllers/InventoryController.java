package ua.edu.ukma.springers.voltstore.inventory.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ua.edu.ukma.springers.voltstore.inventory.domain.entity.StockItem;
import ua.edu.ukma.springers.voltstore.inventory.domain.entity.StockReservation;
import ua.edu.ukma.springers.voltstore.inventory.services.InventoryService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService inventoryService;

    @GetMapping("/stock")
    public ResponseEntity<List<StockItem>> getAllStock() {
        return ResponseEntity.ok(inventoryService.getAllStockItems());
    }

    @GetMapping("/stock/{sku}")
    public ResponseEntity<StockItem> getStockBySku(@PathVariable String sku) {
        return ResponseEntity.ok(inventoryService.getStockItemBySku(sku));
    }

    @PostMapping("/stock")
    public ResponseEntity<StockItem> addStock(@RequestParam String sku,
                                              @RequestParam Integer quantity) {
        return ResponseEntity.ok(inventoryService.addStock(sku, quantity));
    }

    @PatchMapping("/stock/{sku}/details")
    public ResponseEntity<StockItem> updateItemDetails(@PathVariable String sku,
                                                       @RequestParam Integer weightGrams,
                                                       @RequestParam String dimensions) {
        return ResponseEntity.ok(inventoryService.updateItemDetails(sku, weightGrams, dimensions));
    }

    @PostMapping("/reserve")
    public ResponseEntity<StockReservation> reserveStock(@RequestParam UUID orderId,
                                                         @RequestParam String sku,
                                                         @RequestParam Integer quantity) {
        return ResponseEntity.ok(inventoryService.reserveStock(orderId, sku, quantity));
    }

    @PostMapping("/confirm-reservation/{orderId}")
    public ResponseEntity<Void> confirmReservation(@PathVariable UUID orderId) {
        inventoryService.confirmReservation(orderId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/cancel-reservation/{orderId}")
    public ResponseEntity<Void> cancelReservation(@PathVariable UUID orderId) {
        inventoryService.cancelReservation(orderId);
        return ResponseEntity.ok().build();
    }
}