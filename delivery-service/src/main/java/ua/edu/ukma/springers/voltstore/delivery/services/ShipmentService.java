package ua.edu.ukma.springers.voltstore.delivery.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Recipient;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;
import ua.edu.ukma.springers.voltstore.delivery.events.OrderPaidEvent;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
public class ShipmentService {
    private final ShipmentRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ShipmentService(ShipmentRepository repository, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * EP-DEL-EVT-01: a paid order gets a {@code TTN_PENDING} shipment, which the TTN generation job picks up. A
     * shipment that already exists (duplicate event, or a {@code CANCELLED} tombstone written by a cancellation that
     * came first) is left unchanged.
     */
    public void createFromOrderPaid(OrderPaidEvent event) {
        OrderPaidEvent.DeliveryDetails details = event.deliveryDetails();
        boolean created = insertIfAbsent(event.orderId(), () -> Shipment.pending(event.orderId(),
                new Recipient(details.name(), details.phone(), details.address()), details.carrier(),
                event.items().stream().map(item -> new ShipmentItem(item.productId(), item.quantity())).toList(),
                event.totalAmount(), event.currency(), clock.instant()));
        if (created) {
            log.info("Created TTN_PENDING shipment for paid order {} ({} items)", event.orderId(),
                    event.items().size());
        } else {
            log.info("OrderPaid for order {} ignored: a shipment already exists", event.orderId());
        }
    }

    /**
     * Inserts the shipment unless one exists for the order. Race-safe on the unique {@code order_id}: if a
     * concurrent insert (e.g. a cancellation tombstone and a late {@code OrderPaid}) wins, the losing insert fails
     * with a duplicate key, its own transaction is rolled back, and the loser treats it as "already exists".
     *
     * @return whether this call created the shipment
     */
    boolean insertIfAbsent(UUID orderId, Supplier<Shipment> newShipment) {
        if (repository.findByOrderId(orderId).isPresent()) {
            return false;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(newShipment.get()));
            return true;
        } catch (DataIntegrityViolationException e) {
            if (repository.findByOrderId(orderId).isPresent()) {
                log.info("Concurrent insert of the shipment for order {} won; nothing to do", orderId);
                return false;
            }
            throw e;
        }
    }
}
