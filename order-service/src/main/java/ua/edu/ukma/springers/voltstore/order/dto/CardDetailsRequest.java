package ua.edu.ukma.springers.voltstore.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.PaymentCard;
import ua.edu.ukma.springers.voltstore.order.validation.NotExpired;

/**
 * Card data on its way to Payment Service (EP-ORD-03). Validated with Payment's own rules, so a card Order accepts is
 * one Payment accepts. It lives only in memory for the request: never stored, logged or echoed. Validation messages
 * never contain the rejected value, and {@link #toString()} prints the number masked and nothing else.
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
        return "CardDetailsRequest[" + (number == null ? "null" : toPaymentCard().toString()) + "]";
    }
}
