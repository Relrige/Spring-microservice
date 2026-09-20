package ua.edu.ukma.springers.voltstore.delivery.domain.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class DeliverySnapshot {
    private String fullName;
    private String phoneNumber;
    private String city;
    private String warehouseAddress;
}