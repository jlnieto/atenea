package com.atenea.persistence.developmentchange;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevelopmentChangeWorkspaceOperationRepository
        extends JpaRepository<DevelopmentChangeWorkspaceOperationEntity, Long> {

    @EntityGraph(attributePaths = {"operator", "project", "developmentChange"})
    Optional<DevelopmentChangeWorkspaceOperationEntity>
            findByOperationKindAndIdempotencyKey(
                    DevelopmentChangeWorkspaceOperationKind operationKind,
                    UUID idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operation from DevelopmentChangeWorkspaceOperationEntity operation "
            + "join fetch operation.operator join fetch operation.project "
            + "join fetch operation.developmentChange "
            + "where operation.operationId = :operationId")
    Optional<DevelopmentChangeWorkspaceOperationEntity> findByOperationIdForUpdate(
            @Param("operationId") UUID operationId);

    @EntityGraph(attributePaths = {"operator", "project", "developmentChange"})
    Optional<DevelopmentChangeWorkspaceOperationEntity> findByOperationId(UUID operationId);

    @EntityGraph(attributePaths = {"operator", "project", "developmentChange"})
    Optional<DevelopmentChangeWorkspaceOperationEntity>
            findFirstByDevelopmentChangeIdAndStateOrderByRequestedAtDesc(
                    Long developmentChangeId,
                    DevelopmentChangeWorkspaceOperationState state);

    @EntityGraph(attributePaths = {"operator", "project", "developmentChange"})
    List<DevelopmentChangeWorkspaceOperationEntity>
            findAllByStateInOrderByRequestedAtAsc(
                    Collection<DevelopmentChangeWorkspaceOperationState> states);

    @EntityGraph(attributePaths = {"operator", "project", "developmentChange"})
    @Query("select operation from DevelopmentChangeWorkspaceOperationEntity operation "
            + "where operation.state = 'UNCERTAIN' and not exists ("
            + "select successor.id from DevelopmentChangeWorkspaceOperationEntity successor "
            + "where successor.predecessorOperationId = operation.operationId) "
            + "order by operation.requestedAt asc")
    List<DevelopmentChangeWorkspaceOperationEntity> findAllUnreconciledUncertain();

    boolean existsByDevelopmentChangeIdAndStateIn(
            Long developmentChangeId,
            Collection<DevelopmentChangeWorkspaceOperationState> states);
}
