package ua.edu.ukma.springers.voltstore.order.dto;

import java.util.UUID;

/** Inventory's answer for a product it could not reserve, passed to the customer unchanged (EP-ORD-02). */
public record InsufficientItem(UUID productId, int requested, int available) {
}
