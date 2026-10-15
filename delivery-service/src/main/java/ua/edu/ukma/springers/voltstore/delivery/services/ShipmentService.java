package ua.edu.ukma.springers.voltstore.delivery.services;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierException;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Recipient;
import ua.edu.ukma.springers.voltstore.delivery.dto.ShipmentStatusResponse;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.events.OrderPaidEvent;
import ua.edu.ukma.springers.voltstore.delivery.events.OrderShippedEvent;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidQueryParameterException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidShipmentStateException;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ShipmentService {
    static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_CANCEL_ATTEMPTS = 3;
    private static final String ORDER_IDS = "orderIds";

    private final ShipmentRepository repository;
    private final CarrierGateway carrierGateway;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ShipmentService(ShipmentRepository repository, CarrierGateway carrierGateway,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.carrierGateway = carrierGateway;
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
     * EP-DEL-EVT-02: the courier collected the parcel. {@code TTN_GENERATED} → {@code IN_TRANSIT} and commit, then
     * the parcel is handed to the carrier, which reports the delivery later (EP-DEL-04). If the service stops between
     * the commit and the handover, the startup recovery hands it over again ({@link HandoverRecovery}).
     * A duplicate ({@code IN_TRANSIT}, {@code DELIVERED}) is acknowledged; an unknown order or any other status is
     * logged and acknowledged, because Order ships only after checking {@code TTN_GENERATED}.
     */
    public void handOver(OrderShippedEvent event) {
        UUID orderId = event.orderId();
        String ttn = transactionTemplate.execute(status -> {
            Shipment shipment = repository.findByOrderId(orderId).orElse(null);
            if (shipment == null) {
                log.warn("OrderShipped for order {} without a shipment; ignored", orderId);
                return null;
            }
            switch (shipment.getStatus()) {
                case TTN_GENERATED -> {
                    if (event.ttn() != null && !event.ttn().equals(shipment.getTtn())) {
                        log.warn("OrderShipped for order {} names TTN {}, but the shipment has {}; using the "
                                + "shipment's TTN", orderId, event.ttn(), shipment.getTtn());
                    }
                    shipment.markInTransit(clock.instant());
                    return shipment.getTtn();
                }
                case IN_TRANSIT, DELIVERED -> {
                    log.info("OrderShipped for order {} is a duplicate (shipment is {}); nothing to do", orderId,
                            shipment.getStatus());
                    return null;
                }
                default -> {
                    log.warn("OrderShipped for order {} while the shipment is {}; ignored (Order should ship only "
                            + "TTN_GENERATED shipments)", orderId, shipment.getStatus());
                    return null;
                }
            }
        });
        if (ttn != null) {
            carrierGateway.handOver(ttn);
            log.info("Order {} (TTN {}) handed over to the carrier", orderId, ttn);
        }
    }

    /**
     * EP-DEL-02, the cancellation saga step. Allowed until the parcel is handed to the carrier:
     * <ul>
     *   <li>no shipment yet → a {@code CANCELLED} tombstone, so a late {@code OrderPaid} creates nothing;</li>
     *   <li>{@code CANCELLED} → nothing (idempotent);</li>
     *   <li>{@code TTN_PENDING} / {@code TTN_FAILED} → {@code CANCELLED}; no TTN exists. A TTN attempt running
     *   right now sees the change and discards its result;</li>
     *   <li>{@code TTN_GENERATED} → {@code CANCELLED} and commit, then the TTN is cancelled at the carrier. That call
     *   is best-effort: a failure is logged and stored as the failure reason (the TTN is kept for manual clean-up),
     *   and the saga is not blocked;</li>
     *   <li>{@code IN_TRANSIT} / {@code DELIVERED} → {@link InvalidShipmentStateException} (409).</li>
     * </ul>
     */
    public void cancel(UUID orderId) {
        if (insertIfAbsent(orderId, () -> Shipment.cancelledTombstone(orderId))) {
            log.info("Order {} cancelled before its OrderPaid was consumed; stored a CANCELLED tombstone", orderId);
            return;
        }
        String ttnToCancel = cancelInDatabase(orderId);
        if (ttnToCancel == null) {
            return;
        }
        // After the commit: no row lock is held while the carrier is slow
        try {
            carrierGateway.cancel(ttnToCancel);
            log.info("Cancelled TTN {} of order {} at the carrier", ttnToCancel, orderId);
        } catch (CarrierException e) {
            log.error("Order {} is cancelled, but its TTN {} could not be cancelled at the carrier: {}; it is kept "
                    + "for manual clean-up", orderId, ttnToCancel, e.getMessage());
            transactionTemplate.executeWithoutResult(status -> repository.findByOrderId(orderId).orElseThrow()
                    .recordCarrierCancellationFailure(e.getMessage()));
        }
    }

    /** @return the TTN to cancel at the carrier, or null when there is none */
    private @Nullable String cancelInDatabase(UUID orderId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    Shipment shipment = repository.findByOrderId(orderId).orElseThrow();
                    switch (shipment.getStatus()) {
                        case CANCELLED -> {
                            log.info("Order {} is already cancelled; nothing to do", orderId);
                            return null;
                        }
                        case IN_TRANSIT, DELIVERED -> throw new InvalidShipmentStateException(orderId,
                                shipment.getStatus(), "Cannot cancel a shipment that was handed to the carrier");
                        default -> {
                            String ttn = shipment.getStatus() == ShipmentStatus.TTN_GENERATED
                                    ? shipment.getTtn() : null;
                            shipment.cancel();
                            log.info("Shipment of order {} cancelled", orderId);
                            return ttn;
                        }
                    }
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                // The TTN job applied a result at the same moment; read the new state and decide again
                if (attempt == MAX_CANCEL_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    /**
     * EP-DEL-01: one entry per distinct requested order, in request order, loaded with one query. An order without
     * a shipment gets {@code status = null}.
     */
    @Transactional(readOnly = true)
    public List<ShipmentStatusResponse> getStatuses(@Nullable List<UUID> orderIds) {
        // A list such as "?orderIds=a,,b" binds an empty element as null
        if (orderIds == null || orderIds.isEmpty() || orderIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidQueryParameterException(ORDER_IDS, "must be a non-empty list of UUIDs");
        }
        Set<UUID> distinct = new LinkedHashSet<>(orderIds);
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new InvalidQueryParameterException(ORDER_IDS,
                    "must contain at most " + MAX_BATCH_SIZE + " distinct values");
        }
        Map<UUID, Shipment> byOrderId = repository.findAllByOrderIdIn(distinct).stream()
                .collect(Collectors.toMap(Shipment::getOrderId, Function.identity()));
        return distinct.stream()
                .map(orderId -> byOrderId.containsKey(orderId)
                        ? ShipmentStatusResponse.of(byOrderId.get(orderId))
                        : ShipmentStatusResponse.notFound(orderId))
                .toList();
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
