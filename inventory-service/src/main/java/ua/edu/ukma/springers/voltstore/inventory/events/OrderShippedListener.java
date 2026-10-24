package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;

/**
 * EP-INV-EVT-02: a shipped order's reserved stock is deducted for good. Idempotency comes from the reservation
 * status (only ACTIVE reservations are consumed), so a duplicate event finds nothing to do. Offsets and failures
 * are handled as for {@link ProductEventsListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderShippedListener {
    private final ReservationService reservationService;

    @KafkaListener(topics = "${inventory.kafka.topics.order-events}", filter = "orderShippedOnly")
    public void onOrderShipped(OrderShippedEvent event) {
        int consumed = reservationService.consume(event.orderId());
        if (consumed == 0) {
            // Already consumed (duplicate), released (cancelled) or never reserved: nothing to deduct
            log.warn("OrderShipped for order {} found no ACTIVE reservations; nothing deducted", event.orderId());
        } else {
            log.info("Consumed {} reservations of shipped order {} (ttn={})", consumed, event.orderId(), event.ttn());
        }
    }
}
