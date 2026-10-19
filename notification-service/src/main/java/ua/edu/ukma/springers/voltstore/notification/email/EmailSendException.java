package ua.edu.ukma.springers.voltstore.notification.email;

import lombok.Getter;

/**
 * Sending failed. {@code reason} is the stable text stored as the notification's {@code failure_reason}, e.g.
 * {@code smtp_unavailable: ConnectException: Connection refused} or {@code recipient_rejected: 550}; it never contains
 * the recipient's address.
 */
@Getter
public class EmailSendException extends RuntimeException {
    private final String reason;
    private final boolean retryable;

    public EmailSendException(String reason, boolean retryable, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
        this.retryable = retryable;
    }
}
