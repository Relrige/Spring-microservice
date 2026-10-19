package ua.edu.ukma.springers.voltstore.notification.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.DeliveryEventHandler;

/**
 * The one consumer of Delivery's topic (group {@code notification-service}). Only {@code OrderDelivered} has a
 * handler; {@code TTNGenerated} (and any other type) is skipped. Offsets and failures as in
 * {@link OrderEventsListener}; the dead-letter topic is {@code notification.delivery.shipment-events.dlt}.
 */
@Component
public class DeliveryEventsListener {
    private final EventDispatcher dispatcher;

    public DeliveryEventsListener(ObjectProvider<DeliveryEventHandler<?>> handlers, EventReader reader) {
        this.dispatcher = new EventDispatcher(handlers.orderedStream().toList(), reader);
    }

    @KafkaListener(id = "delivery-events", idIsGroup = false, topics = "${notification.kafka.topics.delivery-events}")
    public void onDeliveryEvent(ConsumerRecord<String, String> record) {
        dispatcher.dispatch(record);
    }
}
