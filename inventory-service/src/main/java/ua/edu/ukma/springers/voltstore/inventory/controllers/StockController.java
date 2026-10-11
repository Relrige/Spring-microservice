package ua.edu.ukma.springers.voltstore.inventory.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.inventory.dto.AdjustStockRequest;
import ua.edu.ukma.springers.voltstore.inventory.dto.AdjustStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.dto.SetPackagingRequest;
import ua.edu.ukma.springers.voltstore.inventory.dto.StockBalanceResponse;
import ua.edu.ukma.springers.voltstore.inventory.paging.PageResponse;
import ua.edu.ukma.springers.voltstore.inventory.paging.PageableParser;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/inventory/stock")
@RequiredArgsConstructor
public class StockController {
    // Public sortBy names mapped to entity properties; productId is the tiebreaker (and the default order)
    private static final PageableParser PAGEABLE_PARSER = new PageableParser(Map.of(
            "productId", "productId",
            "availableQuantity", "availableQuantity",
            "reservedQuantity", "reservedQuantity"), "productId");

    private final StockService stockService;

    @GetMapping
    public PageResponse<StockBalanceResponse> listStock(
            @RequestParam(required = false) Boolean packagingConfigured,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageableParser.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String sortBy) {
        return PageResponse.from(
                stockService.listStock(packagingConfigured, PAGEABLE_PARSER.parse(page, size, sortBy)),
                StockBalanceResponse::from);
    }

    @PostMapping("/{productId}/adjust")
    public AdjustStockResponse adjustStock(@PathVariable UUID productId,
                                           @Valid @RequestBody AdjustStockRequest request) {
        return new AdjustStockResponse(stockService.adjustStock(productId, request.delta()));
    }

    /**
     * Internal (Catalog): 204 if the product is ready for activation; otherwise 404 (no StockItem) or
     * 422 (packaging not configured) from the exception handler.
     */
    @GetMapping("/{productId}/readiness")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checkReadiness(@PathVariable UUID productId) {
        stockService.checkReadiness(productId);
    }

    @PutMapping("/{productId}/packaging")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPackaging(@PathVariable UUID productId, @Valid @RequestBody SetPackagingRequest request) {
        stockService.setPackaging(productId, request.grossWeightGrams(), request.lengthCm(),
                request.widthCm(), request.heightCm());
    }
}
