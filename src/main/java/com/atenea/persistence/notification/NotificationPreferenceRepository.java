package com.atenea.persistence.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreferenceEntity, Long> {
    Optional<NotificationPreferenceEntity> findByDeviceIdAndCategory(
            Long deviceId,
            NotificationCategory category);
}
