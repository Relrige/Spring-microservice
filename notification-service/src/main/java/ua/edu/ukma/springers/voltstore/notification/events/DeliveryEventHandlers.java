package ua.edu.ukma.springers.voltstore.notification.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ua.edu.ukma.springers.voltstore.notification.email.EmailRenderer;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.DeliveryEventHandler;
import ua.edu.ukma.springers.voltstore.notification.services.NotificationService;

/**
 * The handler of Delivery's {@code OrderDelivered} (EP-NOTIF-EVT-04). {@code TTNGenerated} has no handler and is
 * skipped by the listener.
 * <p>
 * The notification is recorded <b>without a recipient</b> and the read model is not consulted here. The two topics
 * are consumed independently: after an outage, {@code OrderDelivered} may be read before that order's events on
 * Order's topic. A lookup here would fail, use up the listener's retries within seconds and dead-letter a valid
 * event; resolved by the sender job, the same lag only delays the email.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryEventHandlers {

    @Bean
    DeliveryEventHandler<OrderDeliveredEvent> orderDeliveredHandler(NotificationService service,
                                                                     EmailRenderer renderer) {
        return EventHandlers.delivery(OrderDeliveredEvent.TYPE, OrderDeliveredEvent.class,
                event -> service.recordWithoutRecipient(event.eventId(), event.orderId(),
                        NotificationType.ORDER_DELIVERED, renderer.orderDelivered(event.orderId(), event.ttn())));
    }
}
