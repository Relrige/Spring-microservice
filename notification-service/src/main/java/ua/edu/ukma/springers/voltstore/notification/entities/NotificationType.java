package ua.edu.ukma.springers.voltstore.notification.entities;

// Which email a notification is; stored as its name
public enum NotificationType {
    ORDER_PLACED,
    PAYMENT_RECEIVED,
    ORDER_SHIPPED,
    ORDER_DELIVERED,
    ORDER_CANCELLED,
    ORDER_EXPIRED
}
