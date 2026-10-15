package ua.edu.ukma.springers.voltstore.payment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentCard;
import ua.edu.ukma.springers.voltstore.payment.validation.NotExpired;

/**
 * Card data as received from Order Service. It lives only in memory for the duration of the request: it is passed
 * to the gateway and never stored or logged. Validation messages never contain the rejected value, and
 * {@link #toString()} prints the number masked and nothing else.
 */
@NotExpired
public record CardDetailsRequest(
        @NotNull @Pattern(regexp = "\\d{13,19}", message = "must be 13 to 19 digits") String number,
        @NotNull @Min(1) @Max(12) Integer expiryMonth,
        @NotNull @Min(2000) @Max(2099) Integer expiryYear,
        @NotNull @Pattern(regexp = "\\d{3,4}", message = "must be 3 or 4 digits") String cvv
) {

    public PaymentCard toPaymentCard() {
        return new PaymentCard(number, expiryMonth, expiryYear, cvv);
    }

    @Override
    public String toString() {
        return "CardDetailsRequest[" + (number == null ? "null" : PaymentCard.mask(number)) + "]";
    }
}
