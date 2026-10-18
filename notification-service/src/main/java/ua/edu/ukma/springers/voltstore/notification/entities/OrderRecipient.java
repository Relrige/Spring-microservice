package ua.edu.ukma.springers.voltstore.notification.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model {@code orderId → customerEmail} (spec D1). It is written only by the upsert in
 * {@code OrderRecipientRepository}, and mapped as an entity so the schema is validated and JPQL can query it.
 */
@Entity
@Table(name = "order_recipients")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderRecipient {

    @Id
    private UUID orderId;

    @Column(nullable = false, length = 256)
    private String customerEmail;

    @Column(nullable = false)
    private Instant updatedAt;
}
