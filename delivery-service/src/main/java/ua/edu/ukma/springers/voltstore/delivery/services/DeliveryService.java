package ua.edu.ukma.springers.voltstore.delivery.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.delivery.domain.entity.Consignment;
import ua.edu.ukma.springers.voltstore.delivery.repositories.ConsignmentRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryService {
    private final ConsignmentRepository consignmentRepository;

    @Transactional
    public Consignment createConsignment(Consignment consignment) {
        consignment.setStatus(Consignment.DeliveryStatus.REGISTERED);
        return consignmentRepository.save(consignment);
    }

    @Transactional
    public Consignment updateWaybill(UUID orderId, String waybillNumber) {
        Consignment consignment = consignmentRepository.findByOrderId(orderId).orElseThrow();
        consignment.setWaybillNumber(waybillNumber);
        return consignmentRepository.save(consignment);
    }

    @Transactional
    public void cancelDelivery(UUID orderId) {
        Consignment consignment = consignmentRepository.findByOrderId(orderId).orElseThrow();
        consignment.setStatus(Consignment.DeliveryStatus.CANCELLED);
        consignmentRepository.save(consignment);
    }

    public Consignment getTrackingInfo(String waybillNumber) {
        return consignmentRepository.findByWaybillNumber(waybillNumber).orElseThrow();
    }
}