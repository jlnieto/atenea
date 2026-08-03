package com.atenea.persistence.worksession;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "project")
    @Query("select session from WorkSessionEntity session where session.id = :id")
    Optional<WorkSessionEntity> findLockedWithProjectById(@Param("id") Long id);

    @EntityGraph(attributePaths = "project")
    java.util.List<WorkSessionEntity> findByProjectIdOrderByLastActivityAtDesc(Long projectId);

    @EntityGraph(attributePaths = "project")
    List<WorkSessionEntity> findByStatusInOrderByLastActivityAtDesc(Collection<WorkSessionStatus> statuses);
}
