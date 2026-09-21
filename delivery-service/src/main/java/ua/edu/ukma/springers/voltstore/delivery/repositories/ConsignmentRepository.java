package ua.edu.ukma.springers.voltstore.delivery.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.delivery.domain.entity.Consignment;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsignmentRepository extends JpaRepository<Consignment, UUID> {
    Optional<Consignment> findByOrderId(UUID orderId);
    Optional<Consignment> findByWaybillNumber(String waybillNumber);
}