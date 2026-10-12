package ua.edu.ukma.springers.voltstore.inventory.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;

import java.util.List;

@Getter
public class InsufficientStockForReservationException extends RuntimeException {
    private final List<InsufficientItem> insufficientItems;

    public InsufficientStockForReservationException(List<InsufficientItem> insufficientItems) {
        super("Insufficient stock for one or more items");
        this.insufficientItems = List.copyOf(insufficientItems);
    }
}
