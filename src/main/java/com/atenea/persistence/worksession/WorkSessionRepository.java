package com.atenea.persistence.worksession;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionRepository extends JpaRepository<WorkSessionEntity, Long> {

    boolean existsByProjectIdAndStatus(Long projectId, WorkSessionStatus status);

    Optional<WorkSessionEntity> findByProjectIdAndStatus(Long projectId, WorkSessionStatus status);

    @EntityGraph(attributePaths = "project")
    Optional<WorkSessionEntity> findWithProjectById(Long id);
}
