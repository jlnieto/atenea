package com.atenea.persistence.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    @Query(value = "select nextval('task_id_seq')", nativeQuery = true)
    Long nextTaskId();

    List<TaskEntity> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    List<TaskEntity> findByProjectIdAndBranchStatusInOrderByCreatedAtAsc(Long projectId, List<TaskBranchStatus> branchStatuses);

    Optional<TaskEntity> findByProjectIdAndTitle(Long projectId, String title);

    @EntityGraph(attributePaths = "project")
    Optional<TaskEntity> findWithProjectById(Long id);
}
