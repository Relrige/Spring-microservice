package ua.edu.ukma.springers.voltstore.payment.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import ua.edu.ukma.springers.voltstore.payment.dto.CardDetailsRequest;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.ZoneId;

public class NotExpiredValidator implements ConstraintValidator<NotExpired, CardDetailsRequest> {
    // The shop's time zone: a card expiring this month is accepted until the month ends in Ukraine
    private static final ZoneId SHOP_ZONE = ZoneId.of("Europe/Kyiv");

    @Override
    public boolean isValid(CardDetailsRequest card, ConstraintValidatorContext context) {
        if (card == null || card.expiryMonth() == null || card.expiryYear() == null) {
            return true;
        }
        try {
            return !YearMonth.of(card.expiryYear(), card.expiryMonth()).isBefore(YearMonth.now(SHOP_ZONE));
        } catch (DateTimeException e) {
            // An impossible month is reported by the field's own @Min/@Max
            return true;
        }
    }
}
