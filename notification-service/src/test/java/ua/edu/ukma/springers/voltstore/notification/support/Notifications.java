package ua.edu.ukma.springers.voltstore.notification.support;

import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;

import java.time.Instant;
import java.util.UUID;

// Test data: pending notifications with fixed texts
public final class Notifications {
    public static final String RECIPIENT = "user@example.com";

    private Notifications() {
    }

    public static NotificationEntity pending(Instant now) {
        return pending(UUID.randomUUID(), UUID.randomUUID(), RECIPIENT, now);
    }

    public static NotificationEntity pending(UUID eventId, UUID orderId, String recipient, Instant now) {
        return NotificationEntity.pending(eventId, orderId, NotificationType.ORDER_PLACED, recipient,
                "VoltStore — Order #" + orderId + " placed, awaiting payment", "Body of order " + orderId, now);
    }
}
