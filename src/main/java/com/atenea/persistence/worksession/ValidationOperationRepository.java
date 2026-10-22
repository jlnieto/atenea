package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface ValidationOperationRepository extends JpaRepository<ValidationOperationEntity, UUID> {

    interface PendingResult {
        UUID getId();
        Long getSessionId();
    }

    // A running workflow owns its session's validation sequence. The residual
    // collector only observes results without that durable owner.
    @Query(value = """
            SELECT v.id AS id, v.work_session_id AS sessionId FROM validation_operation v
            WHERE v.status='RUNNING' AND NOT EXISTS (
                SELECT 1 FROM change_validation_workflow w WHERE w.session_id=v.work_session_id AND w.state='RUNNING')
            ORDER BY v.updated_at, v.id
            """, nativeQuery = true)
    List<PendingResult> pendingResults(Pageable page);

    boolean existsByWorkSessionIdAndStatus(Long workSessionId, ValidationOperationStatus status);

    List<ValidationOperationEntity> findByWorkSessionIdAndStatus(Long workSessionId, ValidationOperationStatus status);

    Optional<ValidationOperationEntity> findFirstByWorkSessionIdOrderByStartedAtDescIdDesc(Long workSessionId);

    @EntityGraph(attributePaths = {"workSession", "workSession.project"})
    Optional<ValidationOperationEntity> findByIdentitySha256(String identitySha256);

    @EntityGraph(attributePaths = {"workSession", "workSession.project"})
    List<ValidationOperationEntity> findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
            Long workSessionId,
            String sourceTreeFingerprintSha256);
}
