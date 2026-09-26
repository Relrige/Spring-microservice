package ua.edu.ukma.springers.voltstore.delivery.domain.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "consignments")
@Data
public class Consignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID orderId;

    @Column(nullable = false)
    private String carrier;

    @Column(unique = true)
    private String waybillNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Embedded
    private ParcelSnapshot parcelParams;

    @Embedded
    private DeliverySnapshot recipient;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum DeliveryStatus {
        REGISTERED,
        SHIPPED,
        DELIVERED,
        CANCELLED
    }
}