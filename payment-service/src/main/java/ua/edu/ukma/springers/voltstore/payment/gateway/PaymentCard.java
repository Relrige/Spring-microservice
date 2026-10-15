package ua.edu.ukma.springers.voltstore.payment.gateway;

/**
 * Card data on its way to the provider. It is kept in memory only: never stored and never logged. The record's
 * generated {@code toString()} would print every field, so it is replaced by a masked form.
 */
public record PaymentCard(String number, int expiryMonth, int expiryYear, String cvv) {

    public String lastFour() {
        return number.substring(Math.max(0, number.length() - 4));
    }

    public String masked() {
        return "**** " + lastFour();
    }

    @Override
    public String toString() {
        return "PaymentCard[" + masked() + "]";
    }
}
