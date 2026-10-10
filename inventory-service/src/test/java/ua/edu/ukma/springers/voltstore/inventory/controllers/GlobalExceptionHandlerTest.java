package ua.edu.ukma.springers.voltstore.inventory.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class})
class GlobalExceptionHandlerTest {
    private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.quantity").isNotEmpty())
                .andExpect(jsonPath("$.errors.productId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void malformedJson_returns400WithCommonFields() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void nonUuidPathVariable_returns400WithCommonFields() throws Exception {
        mockMvc.perform(get("/probe/not-a-uuid/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void stockItemNotFound_returns404() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void insufficientStockForWriteOff_returns422() throws Exception {
        mockMvc.perform(get("/probe/insufficient"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient available stock for write-off"));
    }

    @Test
    void insufficientStockForReservation_returns422WithItems() throws Exception {
        mockMvc.perform(get("/probe/insufficient-reservation"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.insufficientItems[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.insufficientItems[0].requested").value(5))
                .andExpect(jsonPath("$.insufficientItems[0].available").value(2));
    }

    @Test
    void reservationAlreadyExists_returns409() throws Exception {
        mockMvc.perform(get("/probe/reservation-exists"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void packagingNotConfigured_returns422WithReason() throws Exception {
        mockMvc.perform(get("/probe/packaging-not-configured"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/packaging-not-configured"))
                .andExpect(jsonPath("$.reason").value("packaging_data_missing"))
                .andExpect(jsonPath("$.service").value("inventory-service"));
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.service").value("inventory-service"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    record ProbeRequest(@NotNull UUID productId, @Positive int quantity) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/body")
        void body(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/{id}/typed")
        void typed(@PathVariable UUID id) {
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new StockItemNotFoundException(PRODUCT_ID);
        }

        @GetMapping("/probe/insufficient")
        void insufficient() {
            throw new InsufficientStockException(PRODUCT_ID);
        }

        @GetMapping("/probe/insufficient-reservation")
        void insufficientReservation() {
            throw new InsufficientStockForReservationException(List.of(new InsufficientItem(PRODUCT_ID, 5, 2)));
        }

        @GetMapping("/probe/reservation-exists")
        void reservationExists() {
            throw new ReservationAlreadyExistsException(UUID.randomUUID());
        }

        @GetMapping("/probe/packaging-not-configured")
        void packagingNotConfigured() {
            throw new PackagingNotConfiguredException(PRODUCT_ID);
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
