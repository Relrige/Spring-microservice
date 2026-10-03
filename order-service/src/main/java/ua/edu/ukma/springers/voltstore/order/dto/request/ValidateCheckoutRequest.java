package ua.edu.ukma.springers.voltstore.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ValidateCheckoutRequest {
    @NotEmpty
    @Valid
    private List<CheckoutItem> items;

    @Data
    public class CheckoutItem {
        @NotNull
        private UUID productId;
        @NotNull
        private Integer quantity;
    }
}
