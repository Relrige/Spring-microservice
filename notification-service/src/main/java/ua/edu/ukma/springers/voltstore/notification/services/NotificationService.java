package ua.edu.ukma.springers.voltstore.notification.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.notification.email.RenderedEmail;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;
import ua.edu.ukma.springers.voltstore.notification.repositories.OrderRecipientRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Records what must be sent; the sender job sends it. Recording is the Idempotent Consumer: the notification is
 * keyed by the event's ID, so a redelivered event inserts nothing and never causes a second email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notifications;
    private final OrderRecipientRepository recipients;
    private final Clock clock;

    /**
     * For an order event (EP-NOTIF-EVT-01/02/03/05), in one transaction: refreshes the recipient read model, then
     * records the notification addressed to {@code customerEmail}. For a duplicate event the upsert still commits;
     * that is harmless, because it writes the same snapshot of the address.
     */
    @Transactional
    public void recordForCustomer(UUID eventId, UUID orderId, String customerEmail, NotificationType type,
                                  RenderedEmail email) {
        Instant now = clock.instant();
        recipients.upsertRecipient(orderId, customerEmail, now);
        insert(NotificationEntity.pending(eventId, orderId, type, customerEmail, email.subject(), email.body(), now));
    }

    /**
     * For an event without an address (Delivery's {@code OrderDelivered}, EP-NOTIF-EVT-04): the sender job resolves
     * the recipient from the read model at send time, so lag between the two topics only delays the email.
     */
    @Transactional
    public void recordWithoutRecipient(UUID eventId, UUID orderId, NotificationType type, RenderedEmail email) {
        insert(NotificationEntity.pending(eventId, orderId, type, null, email.subject(), email.body(),
                clock.instant()));
    }

    private void insert(NotificationEntity notification) {
        if (notifications.insertIfAbsent(notification)) {
            log.info("Recorded {} notification {} for order {} (event {})", notification.getType(),
                    notification.getId(), notification.getOrderId(), notification.getEventId());
        } else {
            log.debug("Event {} of order {} was already recorded; skipping the duplicate", notification.getEventId(),
                    notification.getOrderId());
        }
    }
}
