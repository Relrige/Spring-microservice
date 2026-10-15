package ua.edu.ukma.springers.voltstore.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/** EP-PAY-01. Printing it is safe: {@link CardDetailsRequest#toString()} masks the card. */
public record ChargeRequest(
        @NotNull UUID orderId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = ChargeRequest.SUPPORTED_CURRENCY,
                message = "must be " + ChargeRequest.SUPPORTED_CURRENCY + " (the only supported currency)")
        String currency,
        @NotNull @Valid CardDetailsRequest cardDetails
) {
    // The shop sells in Ukraine only; another ISO 4217 code would need a provider account in that currency
    public static final String SUPPORTED_CURRENCY = "UAH";
}
