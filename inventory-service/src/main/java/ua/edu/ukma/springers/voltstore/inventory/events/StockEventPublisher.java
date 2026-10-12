package ua.edu.ukma.springers.voltstore.inventory.events;

/**
 * Publishes stock events. Callers invoke it inside the transaction that changed the stock, so an
 * implementation can either defer delivery until after the commit (plain Kafka, task 06) or store the
 * event in the same transaction (transactional outbox, task 17) without changing the callers.
 */
public interface StockEventPublisher {
    void publish(StockEvent event);
}
