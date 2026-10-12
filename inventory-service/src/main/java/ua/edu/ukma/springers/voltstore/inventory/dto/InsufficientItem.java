package ua.edu.ukma.springers.voltstore.inventory.dto;

import java.util.UUID;

public record InsufficientItem(UUID productId, int requested, int available) {
}
