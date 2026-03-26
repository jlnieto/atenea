package com.atenea.persistence.worksession;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    boolean existsBySessionIdAndStatus(Long sessionId, AgentRunStatus status);

    @EntityGraph(attributePaths = {"session", "session.project", "originTurn", "resultTurn"})
    List<AgentRunEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
