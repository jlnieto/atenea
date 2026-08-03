package com.atenea.persistence.notification;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, Long> {
    List<NotificationDeliveryEntity> findByEventIdOrderByDeviceIdAsc(UUID eventId);
    long countByEventId(UUID eventId);

    @Query("select delivery.id from NotificationDeliveryEntity delivery "
            + "where delivery.state = com.atenea.persistence.notification.NotificationDeliveryState.PENDING "
            + "or (delivery.state = com.atenea.persistence.notification.NotificationDeliveryState.RETRY_WAIT "
            + "and delivery.nextAttemptAt <= :now) "
            + "order by delivery.id")
    List<Long> findDispatchableIds(Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from NotificationDeliveryEntity delivery "
            + "join fetch delivery.event event "
            + "join fetch event.session session "
            + "join fetch event.agentRun run "
            + "join fetch delivery.device device "
            + "where delivery.id = :id")
    Optional<NotificationDeliveryEntity> findByIdForUpdate(Long id);
}
