package com.atenea.persistence.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationEventRepository extends JpaRepository<NotificationEventEntity, UUID> {
    Optional<NotificationEventEntity> findByDeduplicationSha256(String digest);
}
