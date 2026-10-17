package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;

import java.util.List;

/** Inventory could not reserve every item; nothing was reserved. Answered with 422 and Inventory's list. */
@Getter
public class InsufficientStockException extends RuntimeException {
    private final List<InsufficientItem> insufficientItems;

    public InsufficientStockException(List<InsufficientItem> insufficientItems) {
        super("Insufficient stock for some items");
        this.insufficientItems = List.copyOf(insufficientItems);
    }
}
