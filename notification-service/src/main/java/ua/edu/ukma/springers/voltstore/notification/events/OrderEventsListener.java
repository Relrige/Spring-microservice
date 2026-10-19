package ua.edu.ukma.springers.voltstore.notification.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.notification.events.EventHandler.OrderEventHandler;

/**
 * The one consumer of Order's topic (group {@code notification-service}). {@code OrderPlaced}, {@code OrderPaid},
 * {@code OrderShipped} and {@code OrderCancelled} share the topic and are told apart by the {@code eventType}
 * header, so a single listener dispatches by it to the {@link OrderEventHandler}s. Two listener containers in the
 * same group on the same topic would split the partitions between them, and each would drop the other's types.
 * <p>
 * <b>Offsets</b> are committed after the listener returned (ack mode {@code batch}), so only after the handler's
 * database transaction committed. A redelivered record is harmless: the handlers are idempotent by {@code eventId}.
 * <p>
 * <b>Failures:</b> a payload that is not JSON or breaks a required field is a poison message (no retries, straight
 * to {@code notification.order.order-events.dlt}); other failures are retried by the error handler in
 * {@code KafkaConfig}, then dead-lettered.
 */
@Component
public class OrderEventsListener {
    private final EventDispatcher dispatcher;

    public OrderEventsListener(ObjectProvider<OrderEventHandler<?>> handlers, EventReader reader) {
        this.dispatcher = new EventDispatcher(handlers.orderedStream().toList(), reader);
    }

    // The raw record (not a converted parameter): the JSON is read by the type the eventType header names. The id
    // names the container (tests look it up); idIsGroup = false keeps the group notification-service
    @KafkaListener(id = "order-events", idIsGroup = false, topics = "${notification.kafka.topics.order-events}")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        dispatcher.dispatch(record);
    }
}
