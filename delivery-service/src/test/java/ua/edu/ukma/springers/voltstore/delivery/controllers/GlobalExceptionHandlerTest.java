package ua.edu.ukma.springers.voltstore.delivery.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidQueryParameterException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidShipmentStateException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.ShipmentNotFoundException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.TtnGenerationFailedException;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ProbeController.class})
class GlobalExceptionHandlerTest {
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.quantity").isNotEmpty())
                .andExpect(jsonPath("$.errors.orderId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("delivery-service"));
    }

    @Test
    void malformedJson_returns400WithErrors() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.body").isNotEmpty())
                .andExpect(jsonPath("$.service").value("delivery-service"));
    }

    @Test
    void nonUuidPathVariable_returns400WithErrors() throws Exception {
        mockMvc.perform(get("/probe/not-a-uuid/typed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.orderId[0]").value("must be a valid UUID"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.service").value("delivery-service"));
    }

    @Test
    void invalidQueryParameter_returns400WithErrorsForThatParameter() throws Exception {
        mockMvc.perform(get("/probe/invalid-query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/validation"))
                .andExpect(jsonPath("$.errors.orderIds[0]").value("must contain 1 to 100 values"));
    }

    @Test
    void shipmentNotFound_returns404() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/not-found"))
                .andExpect(jsonPath("$.service").value("delivery-service"));
    }

    @Test
    void invalidShipmentState_returns409() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/conflict"))
                .andExpect(jsonPath("$.detail").value("Retry is only allowed for shipments in TTN_FAILED status"));
    }

    @Test
    void ttnGenerationFailed_returns424WithReason() throws Exception {
        mockMvc.perform(get("/probe/ttn-failed"))
                .andExpect(status().isFailedDependency())
                .andExpect(jsonPath("$.status").value(424))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/ttn-generation-failed"))
                .andExpect(jsonPath("$.reason").value("packaging_data_missing:" + ORDER_ID))
                .andExpect(jsonPath("$.service").value("delivery-service"));
    }

    @Test
    void unexpectedException_returns500WithGenericMessage() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    record ProbeRequest(@NotNull UUID orderId, @Positive int quantity) {
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/body")
        void body(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/probe/{orderId}/typed")
        void typed(@PathVariable UUID orderId) {
        }

        @GetMapping("/probe/invalid-query")
        void invalidQuery() {
            throw new InvalidQueryParameterException("orderIds", "must contain 1 to 100 values");
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new ShipmentNotFoundException(ORDER_ID);
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new InvalidShipmentStateException(ORDER_ID, ShipmentStatus.TTN_PENDING,
                    "Retry is only allowed for shipments in TTN_FAILED status");
        }

        @GetMapping("/probe/ttn-failed")
        void ttnFailed() {
            throw new TtnGenerationFailedException(ORDER_ID, "packaging_data_missing:" + ORDER_ID);
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
