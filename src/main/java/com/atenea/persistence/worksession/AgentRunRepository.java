package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AgentRunEntity run where run.id = :runId")
    Optional<AgentRunEntity> findByIdForUpdate(@Param("runId") Long runId);

    boolean existsBySessionIdAndStatus(Long sessionId, AgentRunStatus status);

    boolean existsBySessionIdAndStatusIn(Long sessionId, List<AgentRunStatus> statuses);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findFirstBySessionIdOrderByCreatedAtDesc(Long sessionId);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findFirstBySessionIdAndOriginTurnIdOrderByCreatedAtAsc(
            Long sessionId,
            Long originTurnId
    );

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findWithSessionById(Long id);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findFirstBySessionIdAndStatusOrderByCreatedAtDesc(Long sessionId, AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findBySessionIdAndStatusOrderByCreatedAtAsc(Long sessionId, AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn", "retryOfRun"})
    Optional<AgentRunEntity> findFirstByRetryOfRunIdOrderByCreatedAtAsc(Long retryOfRunId);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findByStatusOrderByCreatedAtAsc(AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findByExecutionTargetAndStatusInOrderByCreatedAtAsc(
            ExecutionTarget executionTarget,
            List<AgentRunStatus> statuses);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findByDispatchId(java.util.UUID dispatchId);

    @Modifying
    @Query("""
            update AgentRunEntity run
            set run.status = com.atenea.persistence.worksession.AgentRunStatus.FAILED,
                run.processOutcome = com.atenea.persistence.worksession.AgentRunProcessOutcome.FAILED,
                run.finishedAt = :finishedAt,
                run.outputSummary = null,
                run.errorSummary = :errorSummary,
                run.externalTurnId = case
                    when :externalTurnId is null then run.externalTurnId
                    else :externalTurnId
                end
            where run.id = :runId and run.status = com.atenea.persistence.worksession.AgentRunStatus.RUNNING
            """)
    int forceMarkFailedIfRunning(
            @Param("runId") Long runId,
            @Param("externalTurnId") String externalTurnId,
            @Param("errorSummary") String errorSummary,
            @Param("finishedAt") Instant finishedAt);
}
