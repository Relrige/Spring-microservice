package ua.edu.ukma.springers.voltstore.notification.email;

import java.util.Objects;

/** One plain-text email to one recipient. {@code toString} leaves out the address and the body (personal data). */
public record EmailMessage(String to, String subject, String body) {

    public EmailMessage {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }

    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
