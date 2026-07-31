package com.atenea.persistence.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, Long> {
    List<NotificationDeliveryEntity> findByEventIdOrderByDeviceIdAsc(UUID eventId);
    long countByEventId(UUID eventId);
}
