package ua.edu.ukma.springers.voltstore.payment.gateway;

/**
 * Card data on its way to the provider. It is kept in memory only: never stored and never logged. The record's
 * generated {@code toString()} would print every field, so it is replaced by a masked form.
 */
public record PaymentCard(String number, int expiryMonth, int expiryYear, String cvv) {

    public String lastFour() {
        return lastFour(number);
    }

    public String masked() {
        return mask(number);
    }

    /** {@code **** **** **** 4242}: the only form of a card number that may appear in logs or messages. */
    public static String mask(String number) {
        return "**** **** **** " + lastFour(number);
    }

    @Override
    public String toString() {
        return "PaymentCard[" + masked() + "]";
    }

    private static String lastFour(String number) {
        return number.substring(Math.max(0, number.length() - 4));
    }
}
