package ua.edu.ukma.springers.voltstore.order.domain.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class DeliverySnapshot {
    private String recipientName;
    private String phone;
    private String city;
    private String warehouseAddress;
}