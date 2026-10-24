package ua.edu.ukma.springers.voltstore.inventory.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.inventory.dto.ReserveStockItem;
import ua.edu.ukma.springers.voltstore.inventory.dto.ReserveStockRequest;
import ua.edu.ukma.springers.voltstore.inventory.openapi.InsufficientStockProblem;
import ua.edu.ukma.springers.voltstore.inventory.openapi.InternalApi;
import ua.edu.ukma.springers.voltstore.inventory.openapi.Problem;
import ua.edu.ukma.springers.voltstore.inventory.openapi.ValidationProblem;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

@Tag(name = "Reservations")
@RestController
@RequestMapping("/inventory/reservations")
@RequiredArgsConstructor
public class ReservationController {
    private final ReservationService reservationService;

    /**
     * Internal (Order): 204 when stock for all items is held; 409 if the order already holds a reservation,
     * 422 with all short items otherwise.
     */
    @InternalApi
    @Operation(summary = "Reserve stock for an order (EP-INV-06)",
            description = "All or nothing: every item is held, or nothing changes.")
    @ApiResponse(responseCode = "204", description = "Stock for every item is held")
    @ApiResponse(responseCode = "400", description = "Invalid fields, more than 100 items, or a product listed twice",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The order already holds a reservation",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "Not enough stock for one or more items; nothing was reserved",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = InsufficientStockProblem.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reserve(@Valid @RequestBody ReserveStockRequest request) {
        // Product ids are unique here: duplicates were rejected by validation
        reservationService.reserve(request.orderId(), request.items().stream()
                .collect(Collectors.toMap(ReserveStockItem::productId, ReserveStockItem::quantity)));
    }

    /**
     * Internal (Order): 204 whether stock was released now or there was nothing ACTIVE to release (idempotent).
     */
    @InternalApi
    @Operation(summary = "Release an order's reservation (EP-INV-07)",
            description = "Idempotent: also 204 when there is nothing active to release.")
    @ApiResponse(responseCode = "204", description = "Released, or nothing to release")
    @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/{orderId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID orderId) {
        reservationService.release(orderId);
    }
}
