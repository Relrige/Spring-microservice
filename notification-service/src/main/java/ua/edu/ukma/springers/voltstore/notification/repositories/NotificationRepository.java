package ua.edu.ukma.springers.voltstore.notification.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationEntity;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID>, NotificationRepositoryCustom {

    Optional<NotificationEntity> findByEventId(UUID eventId);

    // The pending gauge
    long countByStatus(NotificationStatus status);

    /** Retention: deletes {@code SENT} and {@code FAILED} rows created before {@code cutoff}, never {@code PENDING}. */
    @Transactional
    @Modifying
    @Query("""
            DELETE FROM NotificationEntity n
            WHERE n.status <> ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus.PENDING
              AND n.createdAt < :cutoff
            """)
    int deleteOlderThan(Instant cutoff);
}
