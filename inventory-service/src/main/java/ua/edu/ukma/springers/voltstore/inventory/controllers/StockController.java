package ua.edu.ukma.springers.voltstore.inventory.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.inventory.dto.AdjustStockRequest;
import ua.edu.ukma.springers.voltstore.inventory.dto.AdjustStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.util.UUID;

@RestController
@RequestMapping("/inventory/stock")
@RequiredArgsConstructor
public class StockController {
    private final StockService stockService;

    @PostMapping("/{productId}/adjust")
    public AdjustStockResponse adjustStock(@PathVariable UUID productId,
                                           @Valid @RequestBody AdjustStockRequest request) {
        return new AdjustStockResponse(stockService.adjustStock(productId, request.delta()));
    }
}
