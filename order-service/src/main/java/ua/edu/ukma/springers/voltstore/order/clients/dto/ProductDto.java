package ua.edu.ukma.springers.voltstore.order.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductDto {

    private UUID id;

    private String title;

    private String description;

    private UUID categoryId;

    private BigDecimal price;

    private String specs;

    private ProductActivenessStatus activenessStatus;

    private ProductStockStatus stockStatus;
}
