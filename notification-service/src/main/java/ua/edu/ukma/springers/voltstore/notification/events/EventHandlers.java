package ua.edu.ukma.springers.voltstore.notification.events;

import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.DeliveryEventHandler;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.OrderEventHandler;

import java.util.function.Consumer;

// Builds an EventHandler from its event type, payload record and action, so each handler is a single bean method
final class EventHandlers {

    private EventHandlers() {
    }

    static <T> OrderEventHandler<T> order(String eventType, Class<T> payloadType, Consumer<T> action) {
        return new OrderEventHandler<>() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public Class<T> payloadType() {
                return payloadType;
            }

            @Override
            public void handle(T event) {
                action.accept(event);
            }
        };
    }

    static <T> DeliveryEventHandler<T> delivery(String eventType, Class<T> payloadType, Consumer<T> action) {
        return new DeliveryEventHandler<>() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public Class<T> payloadType() {
                return payloadType;
            }

            @Override
            public void handle(T event) {
                action.accept(event);
            }
        };
    }
}
