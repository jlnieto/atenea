package com.atenea.persistence.worksession;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionRepository extends JpaRepository<WorkSessionEntity, Long> {

    boolean existsByProjectIdAndStatus(Long projectId, WorkSessionStatus status);

    boolean existsByProjectIdAndStatusIn(Long projectId, Collection<WorkSessionStatus> statuses);

    @EntityGraph(attributePaths = "project")
    Optional<WorkSessionEntity> findByProjectIdAndStatus(Long projectId, WorkSessionStatus status);

    @EntityGraph(attributePaths = "project")
    Optional<WorkSessionEntity> findFirstByProjectIdAndStatusInOrderByCreatedAtAsc(
            Long projectId,
            Collection<WorkSessionStatus> statuses
    );

    @EntityGraph(attributePaths = "project")
    Optional<WorkSessionEntity> findFirstByProjectIdOrderByLastActivityAtDesc(Long projectId);

    @EntityGraph(attributePaths = "project")
    Optional<WorkSessionEntity> findWithProjectById(Long id);

    @EntityGraph(attributePaths = "project")
    java.util.List<WorkSessionEntity> findByProjectIdOrderByLastActivityAtDesc(Long projectId);

    @EntityGraph(attributePaths = "project")
    List<WorkSessionEntity> findByStatusInOrderByLastActivityAtDesc(Collection<WorkSessionStatus> statuses);
}
