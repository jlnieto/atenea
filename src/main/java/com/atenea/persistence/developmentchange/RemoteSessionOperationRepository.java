package com.atenea.persistence.developmentchange;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteSessionOperationRepository
        extends JpaRepository<RemoteSessionOperationEntity, Long> {

    @EntityGraph(attributePaths = {"operator", "project", "developmentChange", "workSession"})
    Optional<RemoteSessionOperationEntity>
            findByOperatorIdAndOperationKindAndIdempotencyKey(
                    Long operatorId,
                    RemoteSessionOperationKind operationKind,
                    UUID idempotencyKey);

    boolean existsByDevelopmentChangeIdAndState(
            Long developmentChangeId,
            RemoteSessionOperationState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"operator", "project", "developmentChange", "workSession"})
    @Query("select operation from RemoteSessionOperationEntity operation "
            + "where operation.state = :state order by operation.requestedAt, operation.id")
    List<RemoteSessionOperationEntity> findAllByStateForUpdate(
            @Param("state") RemoteSessionOperationState state);
}
