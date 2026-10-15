package ua.edu.ukma.springers.voltstore.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** EP-PAY-02. Only full refunds exist; amount and currency are checked against the charge. */
public record RefundRequest(
        @NotNull UUID orderId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = ChargeRequest.SUPPORTED_CURRENCY,
                message = "must be " + ChargeRequest.SUPPORTED_CURRENCY + " (the only supported currency)")
        String currency
) {
}
