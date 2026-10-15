package ua.edu.ukma.springers.voltstore.inventory.events;

/**
 * Publishes stock events. Callers invoke it inside the transaction that changed the stock; the implementation
 * ({@code OutboxStockEventPublisher}, task 17) stores the event in the same transaction (transactional outbox).
 * Until task 17 an implementation sent it with a plain KafkaTemplate after the commit; the callers did not change.
 */
public interface StockEventPublisher {
    void publish(StockEvent event);
}
