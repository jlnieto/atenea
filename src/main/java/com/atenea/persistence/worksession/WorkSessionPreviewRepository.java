package com.atenea.persistence.worksession;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkSessionPreviewRepository extends JpaRepository<WorkSessionPreviewEntity, UUID> {

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    Optional<WorkSessionPreviewEntity> findFirstByWorkSessionIdOrderByCreatedAtDescIdDesc(Long workSessionId);

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    Optional<WorkSessionPreviewEntity> findByIdAndWorkSessionId(UUID id, Long workSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    @Query("select preview from WorkSessionPreviewEntity preview where preview.id = :id")
    Optional<WorkSessionPreviewEntity> findLockedById(@Param("id") UUID id);

    boolean existsByWorkSessionIdAndStateIn(Long workSessionId, Collection<PreviewState> states);

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    List<WorkSessionPreviewEntity> findByStateInOrderByCreatedAtAscIdAsc(Collection<PreviewState> states);

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    List<WorkSessionPreviewEntity> findByWorkSessionIdAndAuditRetainUntilAfterOrderByCreatedAtDescIdDesc(
            Long workSessionId,
            Instant now
    );
}
