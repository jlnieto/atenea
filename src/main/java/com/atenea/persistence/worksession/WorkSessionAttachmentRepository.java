package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkSessionAttachmentRepository
        extends JpaRepository<WorkSessionAttachmentEntity, UUID> {

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    Optional<WorkSessionAttachmentEntity> findByIdAndWorkSessionId(UUID id, Long workSessionId);

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    List<WorkSessionAttachmentEntity> findByWorkSessionIdOrderByCreatedAtDescIdDesc(
            Long workSessionId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    List<WorkSessionAttachmentEntity> findByWorkSessionIdAndKindOrderByCreatedAtDescIdDesc(
            Long workSessionId,
            AttachmentKind kind,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"workSession", "workSession.project", "project", "agentRun"})
    List<WorkSessionAttachmentEntity> findByWorkSessionIdAndKindAndSourceOrderByCreatedAtDescIdDesc(
            Long workSessionId,
            AttachmentKind kind,
            AttachmentSource source,
            Pageable pageable
    );

    @Query("""
            select coalesce(sum(attachment.sizeBytes), 0)
            from WorkSessionAttachmentEntity attachment
            where attachment.workSession.id = :workSessionId
            """)
    long sumSizeBytesByWorkSessionId(@Param("workSessionId") Long workSessionId);
}
