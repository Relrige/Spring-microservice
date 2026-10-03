package ua.edu.ukma.springers.voltstore.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDto(
        UUID id,
        String sku,
        String title,
        String category,
        BigDecimal price
) {}