package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.events.ShipmentMilestoneEvent;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;

/**
 * EP-ORD-EVT-01 and EP-ORD-EVT-02: Delivery's milestones move the order on, but only from the status they belong
 * to. Anything else is a duplicate or a late event and changes nothing, which is what makes redelivery harmless
 * (idempotency by state). A concurrent change of the same order fails on its version at commit; the error handler
 * then retries the record, and the retry reads the new status.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentProgressService {
    private final OrderRepository orders;

    /** {@code PAID → PENDING_SHIPPING}. */
    @Transactional
    public void ttnGenerated(ShipmentMilestoneEvent event) {
        OrderEntity order = orders.findById(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("TTNGenerated {} for unknown order {}; ignoring it", event.eventId(), event.orderId());
            return;
        }
        if (order.getStatus() != OrderStatus.PAID) {
            // PENDING_SHIPPING: a duplicate; SHIPPED: the worker was faster; cancelling or cancelled: Delivery
            // cancels the TTN itself
            log.info("TTNGenerated {} for order {} in status {}; nothing to change", event.eventId(), order.getId(),
                    order.getStatus());
            return;
        }
        order.markPendingShipping();
        log.info("Order {} is pending shipping with TTN {}", order.getId(), event.ttn());
    }

    /** {@code SHIPPED → DELIVERED}. */
    @Transactional
    public void delivered(ShipmentMilestoneEvent event) {
        OrderEntity order = orders.findById(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("OrderDelivered {} for unknown order {}; ignoring it", event.eventId(), event.orderId());
            return;
        }
        switch (order.getStatus()) {
            case SHIPPED -> {
                order.markDelivered();
                log.info("Order {} delivered", order.getId());
            }
            case DELIVERED -> log.info("OrderDelivered {} for order {} again; a duplicate", event.eventId(),
                    order.getId());
            // Delivered without being shipped in Order: worth a look, but nothing Order can fix
            default -> log.warn("OrderDelivered {} for order {} in status {}; ignoring it", event.eventId(),
                    order.getId(), order.getStatus());
        }
    }
}
