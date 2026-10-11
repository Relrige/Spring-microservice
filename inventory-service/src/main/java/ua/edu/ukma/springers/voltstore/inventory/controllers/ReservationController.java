package ua.edu.ukma.springers.voltstore.inventory.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.inventory.dto.ReserveStockItem;
import ua.edu.ukma.springers.voltstore.inventory.dto.ReserveStockRequest;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/inventory/reservations")
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationService reservationService;

    /**
     * Internal (Order): 204 when stock for all items is held; 409 if the order already holds a reservation,
     * 422 with all short items otherwise.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@Valid @RequestBody ReserveStockRequest request) {
        // Product ids are unique here: duplicates were rejected by validation
        reservationService.reserve(request.orderId(), request.items().stream()
                .collect(Collectors.toMap(ReserveStockItem::productId, ReserveStockItem::quantity)));
    }
}
