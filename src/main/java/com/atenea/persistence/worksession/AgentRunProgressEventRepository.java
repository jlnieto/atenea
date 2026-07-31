package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunProgressEventRepository
        extends JpaRepository<AgentRunProgressEventEntity, Long> {

    Optional<AgentRunProgressEventEntity> findFirstByAgentRunIdOrderBySequenceDesc(Long agentRunId);

    List<AgentRunProgressEventEntity> findByAgentRunIdAndSequenceGreaterThanOrderBySequenceAsc(
            Long agentRunId,
            long sequence);

    List<AgentRunProgressEventEntity> findByAgentRunIdOrderBySequenceAsc(Long agentRunId);

    long countByAgentRunId(Long agentRunId);

    @Modifying
    @Query("delete from AgentRunProgressEventEntity event "
            + "where event.agentRun.id = :runId and event.sequence < :retainedFloor")
    int deleteBelowRetainedFloor(
            @Param("runId") Long runId,
            @Param("retainedFloor") long retainedFloor);
}
