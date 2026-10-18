package ua.edu.ukma.springers.voltstore.order.repositories;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.order.entities.ReservationAttempt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationAttemptRepository extends JpaRepository<ReservationAttempt, UUID> {

    /** Attempts old enough that any reservation call for them has finished; the oldest first. */
    List<ReservationAttempt> findByCreatedAtLessThanEqualOrderByCreatedAtAsc(Instant settledBefore, Limit limit);
}
