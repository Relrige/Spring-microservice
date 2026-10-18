package ua.edu.ukma.springers.voltstore.notification.events;

/**
 * Handles one event type of a consumed topic. A listener reads the record into {@link #payloadType()} and calls
 * {@link #handle}; types without a handler are skipped. Implement {@link OrderEventHandler} or
 * {@link DeliveryEventHandler} to say which topic the type belongs to.
 *
 * @param <T> Notification's own record of the event (only the fields it reads)
 */
public interface EventHandler<T> {

    // The value of the eventType header, e.g. "OrderPlaced"
    String eventType();

    Class<T> payloadType();

    void handle(T event);

    // A handler of an event on Order's topic (order.order-events)
    interface OrderEventHandler<T> extends EventHandler<T> {
    }

    // A handler of an event on Delivery's topic (delivery.shipment-events)
    interface DeliveryEventHandler<T> extends EventHandler<T> {
    }
}
