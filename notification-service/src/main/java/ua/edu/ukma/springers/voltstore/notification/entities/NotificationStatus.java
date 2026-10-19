package ua.edu.ukma.springers.voltstore.notification.entities;

/**
 * {@code PENDING} → {@code SENT} once the SMTP server accepted the message, or → {@code FAILED} when the address
 * was rejected or the retries are used up. Both end states are final.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
