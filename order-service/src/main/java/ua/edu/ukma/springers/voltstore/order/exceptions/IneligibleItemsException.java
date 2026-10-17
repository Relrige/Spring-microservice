package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;
import ua.edu.ukma.springers.voltstore.order.dto.IneligibleItem;

import java.util.List;

/** Some products are not active, unknown, or out of stock. Answered with 422 and the full list. */
@Getter
public class IneligibleItemsException extends RuntimeException {
    private final List<IneligibleItem> ineligibleItems;

    public IneligibleItemsException(List<IneligibleItem> ineligibleItems) {
        super("Some items cannot be ordered");
        this.ineligibleItems = List.copyOf(ineligibleItems);
    }
}
