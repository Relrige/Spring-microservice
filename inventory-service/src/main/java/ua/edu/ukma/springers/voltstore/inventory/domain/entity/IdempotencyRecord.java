package ua.edu.ukma.springers.voltstore.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
@Data
public class IdempotencyRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private UUID reservationId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}