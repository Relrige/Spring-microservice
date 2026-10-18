package ua.edu.ukma.springers.voltstore.notification.email;

// A subject and a plain-text body, stored on the notification row as they will be sent
public record RenderedEmail(String subject, String body) {
}
