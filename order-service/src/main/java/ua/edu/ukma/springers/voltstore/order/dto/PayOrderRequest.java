package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** EP-ORD-03. The amount is the order's total, never taken from the client. */
public record PayOrderRequest(@NotNull(message = "Card details are required") @Valid CardDetailsRequest cardDetails) {
}
