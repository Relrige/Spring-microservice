package ua.edu.ukma.springers.voltstore.order.repositories;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.order.entities.PendingRefund;

import java.util.List;
import java.util.UUID;

@Repository
public interface PendingRefundRepository extends JpaRepository<PendingRefund, UUID> {

    /** Refunds still owed, the longest waiting first; the job applies the back-off per refund. */
    List<PendingRefund> findAllByOrderByLastAttemptAtAsc(Limit limit);
}
