package ua.edu.ukma.springers.voltstore.delivery.events;

/**
 * Publishes shipment events. Callers invoke it inside the transaction that changed the shipment's status; the
 * implementation ({@code OutboxShipmentEventPublisher}) stores the event in the same transaction (transactional
 * outbox), and a relay sends it to Kafka afterwards.
 */
public interface ShipmentEventPublisher {
    void publish(ShipmentEvent event);
}
