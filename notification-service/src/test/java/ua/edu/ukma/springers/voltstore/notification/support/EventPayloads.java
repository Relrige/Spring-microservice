package ua.edu.ukma.springers.voltstore.notification.support;

import java.util.UUID;

/**
 * JSON payloads exactly as Order and Delivery publish them (Event Catalogue), including the fields Notification
 * ignores (customerId, deliveryDetails, items, timestamp).
 */
public final class EventPayloads {
    public static final String EMAIL = "user@example.com";

    private EventPayloads() {
    }

    public static String orderPlaced(UUID eventId, UUID orderId) {
        return """
                {"eventId":"%s","orderId":"%s","customerId":"%s","customerEmail":"%s","totalAmount":1500.00,
                 "currency":"UAH","reservationExpiresAt":"2026-10-15T12:15:00Z","timestamp":"2026-10-15T12:00:00Z"}
                """.formatted(eventId, orderId, UUID.randomUUID(), EMAIL);
    }

    public static String orderPaid(UUID eventId, UUID orderId) {
        return """
                {"eventId":"%s","orderId":"%s","customerId":"%s","customerEmail":"%s",
                 "deliveryDetails":{"name":"Ivan Petrenko","phone":"+380501234567","address":"Kyiv, branch 12",
                                    "carrier":"NOVA_POSHTA"},
                 "items":[{"productId":"%s","quantity":2}],"totalAmount":1500.00,"currency":"UAH",
                 "paidAt":"2026-10-15T12:05:00Z","timestamp":"2026-10-15T12:05:01Z"}
                """.formatted(eventId, orderId, UUID.randomUUID(), EMAIL, UUID.randomUUID());
    }

    public static String orderShipped(UUID eventId, UUID orderId) {
        return """
                {"eventId":"%s","orderId":"%s","ttn":"20450000123456","customerEmail":"%s",
                 "timestamp":"2026-10-16T09:00:00Z"}
                """.formatted(eventId, orderId, EMAIL);
    }

    public static String orderCancelled(UUID eventId, UUID orderId, String reason) {
        return """
                {"eventId":"%s","orderId":"%s","customerId":"%s","customerEmail":"%s","reason":"%s",
                 "timestamp":"2026-10-15T12:20:00Z"}
                """.formatted(eventId, orderId, UUID.randomUUID(), EMAIL, reason);
    }

    public static String orderDelivered(UUID eventId, UUID orderId) {
        return """
                {"eventId":"%s","orderId":"%s","ttn":"20450000123456","timestamp":"2026-10-17T15:00:00Z"}
                """.formatted(eventId, orderId);
    }

    public static String ttnGenerated(UUID eventId, UUID orderId) {
        return """
                {"eventId":"%s","orderId":"%s","ttn":"20450000123456","carrier":"NOVA_POSHTA",
                 "timestamp":"2026-10-16T08:00:00Z"}
                """.formatted(eventId, orderId);
    }
}
