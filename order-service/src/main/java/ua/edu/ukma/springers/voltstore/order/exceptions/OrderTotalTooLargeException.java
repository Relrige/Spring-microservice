package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * The order's total, at Catalog's prices, exceeds what Payment can charge in one transaction (10 integer digits, the
 * same limit as {@code orders.total_amount}). Nothing was reserved. Answered with 422.
 */
@Getter
public class OrderTotalTooLargeException extends RuntimeException {
    private final BigDecimal maxTotal;

    public OrderTotalTooLargeException(BigDecimal maxTotal) {
        super("The order total exceeds the largest amount that can be paid at once (" + maxTotal.toPlainString()
                + "); split it into several orders");
        this.maxTotal = maxTotal;
    }
}
