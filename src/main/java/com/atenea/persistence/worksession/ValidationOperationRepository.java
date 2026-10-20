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

    @Query("select v.id as id, v.workSession.id as sessionId from ValidationOperationEntity v "
            + "where v.status = com.atenea.persistence.worksession.ValidationOperationStatus.RUNNING "
            + "order by v.updatedAt, v.id")
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
