package ua.edu.ukma.springers.voltstore.delivery.domain.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;
import java.math.BigDecimal;

@Embeddable
@Data
public class ParcelSnapshot {
    private Double weightKg;
    private String dimensions;
    private BigDecimal declaredValue;
}