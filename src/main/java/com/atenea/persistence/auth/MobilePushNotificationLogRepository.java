package com.atenea.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MobilePushNotificationLogRepository extends JpaRepository<MobilePushNotificationLogEntity, Long> {

    boolean existsByEventKey(String eventKey);
}
