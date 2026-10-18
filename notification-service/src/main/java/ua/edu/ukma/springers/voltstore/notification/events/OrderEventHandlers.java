package ua.edu.ukma.springers.voltstore.notification.events;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ua.edu.ukma.springers.voltstore.notification.email.EmailRenderer;
import ua.edu.ukma.springers.voltstore.notification.email.RenderedEmail;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.OrderEventHandler;
import ua.edu.ukma.springers.voltstore.notification.services.NotificationService;

/**
 * The handlers of Order's four events (EP-NOTIF-EVT-01/02/03/05): render the email, then record it with the
 * customer's address and refresh the recipient read model, in one transaction
 * ({@link NotificationService#recordForCustomer}).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class OrderEventHandlers {

    @Bean
    OrderEventHandler<OrderPlacedEvent> orderPlacedHandler(NotificationService service, EmailRenderer renderer) {
        return EventHandlers.order(OrderPlacedEvent.TYPE, OrderPlacedEvent.class, event -> service.recordForCustomer(
                event.eventId(), event.orderId(), event.customerEmail(), NotificationType.ORDER_PLACED,
                renderer.orderPlaced(event.orderId(), event.totalAmount(), event.currency(),
                        event.reservationExpiresAt())));
    }

    @Bean
    OrderEventHandler<OrderPaidEvent> orderPaidHandler(NotificationService service, EmailRenderer renderer) {
        return EventHandlers.order(OrderPaidEvent.TYPE, OrderPaidEvent.class, event -> service.recordForCustomer(
                event.eventId(), event.orderId(), event.customerEmail(), NotificationType.PAYMENT_RECEIVED,
                renderer.paymentReceived(event.orderId(), event.totalAmount(), event.currency(), event.paidAt())));
    }

    @Bean
    OrderEventHandler<OrderShippedEvent> orderShippedHandler(NotificationService service, EmailRenderer renderer) {
        return EventHandlers.order(OrderShippedEvent.TYPE, OrderShippedEvent.class, event -> service.recordForCustomer(
                event.eventId(), event.orderId(), event.customerEmail(), NotificationType.ORDER_SHIPPED,
                renderer.orderShipped(event.orderId(), event.ttn())));
    }

    @Bean
    OrderEventHandler<OrderCancelledEvent> orderCancelledHandler(NotificationService service,
                                                                 EmailRenderer renderer) {
        return EventHandlers.order(OrderCancelledEvent.TYPE, OrderCancelledEvent.class, event -> {
            NotificationType type;
            RenderedEmail email;
            if (OrderCancelledEvent.EXPIRED.equals(event.reason())) {
                type = NotificationType.ORDER_EXPIRED;
                email = renderer.orderExpired(event.orderId());
            } else {
                if (!OrderCancelledEvent.CUSTOMER_CANCELLED.equals(event.reason())) {
                    // A reason Order added later: the generic cancellation email is still correct for the customer
                    log.warn("OrderCancelled event {} of order {} has unknown reason {}; sending the cancellation "
                            + "email", event.eventId(), event.orderId(), event.reason());
                }
                type = NotificationType.ORDER_CANCELLED;
                email = renderer.orderCancelled(event.orderId());
            }
            service.recordForCustomer(event.eventId(), event.orderId(), event.customerEmail(), type, email);
        });
    }
}
