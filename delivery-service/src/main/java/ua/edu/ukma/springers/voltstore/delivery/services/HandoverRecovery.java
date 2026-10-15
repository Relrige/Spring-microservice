package ua.edu.ukma.springers.voltstore.delivery.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ShipmentRepository;

import java.util.List;

/**
 * Restart recovery for the in-process mock carrier: it keeps its scheduled delivery reports in memory, so they are
 * lost when the pod stops, and a crash between the {@code IN_TRANSIT} commit and the handover loses the handover.
 * At startup every {@code IN_TRANSIT} shipment is handed over again. Safe because {@code handOver} and the delivery
 * report are idempotent; with several replicas more than one may report the same delivery, and the second report
 * changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoverRecovery {
    private final ShipmentRepository repository;
    private final CarrierGateway carrierGateway;

    @EventListener(ApplicationReadyEvent.class)
    public void handOverInTransitShipmentsAgain() {
        List<Shipment> inTransit = repository.findAllByStatus(ShipmentStatus.IN_TRANSIT);
        inTransit.forEach(shipment -> carrierGateway.handOver(shipment.getTtn()));
        log.info("Restart recovery: handed {} IN_TRANSIT shipments over to the carrier again", inTransit.size());
    }
}
