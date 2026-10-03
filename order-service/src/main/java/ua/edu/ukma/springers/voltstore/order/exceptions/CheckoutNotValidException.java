package ua.edu.ukma.springers.voltstore.order.exceptions;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class CheckoutNotValidException extends RuntimeException {
    private final List<UUID> notActiveProducts;
    private final List<UUID> outOfStockProducts;
}
