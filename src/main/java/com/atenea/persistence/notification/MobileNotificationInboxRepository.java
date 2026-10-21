package com.atenea.persistence.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MobileNotificationInboxRepository extends JpaRepository<MobileNotificationInboxEntity, UUID> {
    boolean existsByOperatorIdAndEventKey(Long operatorId, String eventKey);
    long countByOperatorIdAndReadAtIsNull(Long operatorId);
    Slice<MobileNotificationInboxEntity> findByOperatorIdOrderByCreatedAtDesc(Long operatorId, Pageable pageable);
    Optional<MobileNotificationInboxEntity> findByIdAndOperatorId(UUID id, Long operatorId);
    Optional<MobileNotificationInboxEntity> findByOperatorIdAndEventKey(Long operatorId, String eventKey);

    @Modifying
    @Query("UPDATE MobileNotificationInboxEntity item SET item.readAt = :readAt " +
            "WHERE item.operator.id = :operatorId AND item.readAt IS NULL AND item.createdAt <= :readAt")
    int markAllRead(@Param("operatorId") Long operatorId, @Param("readAt") Instant readAt);
}
