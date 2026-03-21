package com.atenea.persistence.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<TaskEntity, Long> {

    List<TaskEntity> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    Optional<TaskEntity> findByProjectIdAndTitle(Long projectId, String title);

    @EntityGraph(attributePaths = "project")
    Optional<TaskEntity> findWithProjectById(Long id);
}
