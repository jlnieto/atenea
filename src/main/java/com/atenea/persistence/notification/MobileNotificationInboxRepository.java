package com.atenea.persistence.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MobileNotificationInboxRepository extends JpaRepository<MobileNotificationInboxEntity, UUID> {
    boolean existsByOperatorIdAndEventKey(Long operatorId, String eventKey);
    long countByOperatorIdAndReadAtIsNull(Long operatorId);
    Slice<MobileNotificationInboxEntity> findByOperatorIdOrderByCreatedAtDesc(Long operatorId, Pageable pageable);
    Optional<MobileNotificationInboxEntity> findByIdAndOperatorId(UUID id, Long operatorId);
    Optional<MobileNotificationInboxEntity> findByOperatorIdAndEventKey(Long operatorId, String eventKey);
}
