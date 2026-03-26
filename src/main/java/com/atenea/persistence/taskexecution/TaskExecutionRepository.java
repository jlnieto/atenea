package com.atenea.persistence.taskexecution;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskExecutionRepository extends JpaRepository<TaskExecutionEntity, Long> {

    boolean existsByTaskId(Long taskId);

    @EntityGraph(attributePaths = {"task", "task.project"})
    List<TaskExecutionEntity> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    @EntityGraph(attributePaths = {"task", "task.project"})
    List<TaskExecutionEntity> findByStatusOrderByCreatedAtDesc(TaskExecutionStatus status);

    Optional<TaskExecutionEntity> findFirstByTaskIdOrderByCreatedAtDesc(Long taskId);

    @EntityGraph(attributePaths = {"task", "task.project"})
    List<TaskExecutionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @EntityGraph(attributePaths = {"task", "task.project"})
    List<TaskExecutionEntity> findByStatusOrderByCreatedAtDesc(TaskExecutionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"task", "task.project"})
    List<TaskExecutionEntity> findByTaskProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);

    @EntityGraph(attributePaths = {"task", "task.project"})
    List<TaskExecutionEntity> findByTaskProjectIdAndStatusOrderByCreatedAtDesc(
            Long projectId,
            TaskExecutionStatus status,
            Pageable pageable
    );
}
