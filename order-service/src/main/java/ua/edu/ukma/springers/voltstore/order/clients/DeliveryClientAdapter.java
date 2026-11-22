package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.DeliveryHttpClient;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.ShipmentResponse;
import ua.edu.ukma.springers.voltstore.order.ports.Shipments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Delivery's shipment statuses and cancellation (EP-DEL-01/02). Both are idempotent, so both are retried. Delivery
 * accepts at most 100 IDs per call, so longer lists (a large fulfillment queue) are fetched in chunks; a retry
 * repeats the whole list, which is harmless for a read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryClientAdapter implements Shipments {
    static final String NAME = "delivery";
    static final int CHUNK_SIZE = 100;

    private final DeliveryHttpClient client;

    @Override
    @Retry(name = NAME, fallbackMethod = "findUnavailable")
    @CircuitBreaker(name = NAME)
    public List<ShipmentInfo> find(Collection<UUID> orderIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(orderIds));
        List<ShipmentInfo> result = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
            List<UUID> chunk = ids.subList(from, Math.min(from + CHUNK_SIZE, ids.size()));
            for (ShipmentResponse shipment : client.shipments(chunk)) {
                result.add(new ShipmentInfo(shipment.orderId(), status(shipment), shipment.ttn(),
                        shipment.failureReason()));
            }
        }
        return result;
    }

    @Override
    @Retry(name = NAME, fallbackMethod = "cancelUnavailable")
    @CircuitBreaker(name = NAME)
    public void cancel(UUID orderId) {
        client.cancel(orderId);
    }

    private static DeliveryStatus status(ShipmentResponse shipment) {
        if (shipment.status() == null) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(shipment.status());
        } catch (IllegalArgumentException _) {
            // A status Delivery added later: show the order without it rather than fail the whole answer
            log.warn("Unknown delivery status {} for order {}", shipment.status(), shipment.orderId());
            return null;
        }
    }

    @SuppressWarnings("java:S1172") // Resilience4j finds the fallback by the protected method's parameters
    private List<ShipmentInfo> findUnavailable(Collection<UUID> ignoredOrderIds, Throwable failure) {
        throw ClientFailures.unavailable("Delivery", failure);
    }

    @SuppressWarnings("java:S1172") // Resilience4j finds the fallback by the protected method's parameters
    private void cancelUnavailable(UUID ignoredOrderId, Throwable failure) {
        throw ClientFailures.unavailable("Delivery", failure);
    }
}
