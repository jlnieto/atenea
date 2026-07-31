package com.atenea.persistence.worksession;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunRecoveryOperationRepository
        extends JpaRepository<AgentRunRecoveryOperationEntity, Long> {

    Optional<AgentRunRecoveryOperationEntity> findByOperatorIdAndIdempotencyKey(
            Long operatorId,
            UUID idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from AgentRunRecoveryOperationEntity operation "
            + "where operation.operationId = :operationId")
    Optional<AgentRunRecoveryOperationEntity> findByOperationIdForUpdate(
            @Param("operationId") UUID operationId);
}
