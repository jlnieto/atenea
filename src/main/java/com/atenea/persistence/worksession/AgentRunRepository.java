package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    boolean existsBySessionIdAndStatus(Long sessionId, AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findFirstBySessionIdOrderByCreatedAtDesc(Long sessionId);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findWithSessionById(Long id);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    Optional<AgentRunEntity> findFirstBySessionIdAndStatusOrderByCreatedAtDesc(Long sessionId, AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findBySessionIdAndStatusOrderByCreatedAtAsc(Long sessionId, AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findByStatusOrderByCreatedAtAsc(AgentRunStatus status);

    @Modifying
    @Query("""
            update AgentRunEntity run
            set run.status = com.atenea.persistence.worksession.AgentRunStatus.FAILED,
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
