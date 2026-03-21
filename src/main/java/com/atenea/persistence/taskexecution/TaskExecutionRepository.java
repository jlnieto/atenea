package com.atenea.persistence.taskexecution;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskExecutionRepository extends JpaRepository<TaskExecutionEntity, Long> {

    List<TaskExecutionEntity> findByTaskIdOrderByCreatedAtDesc(Long taskId);

    List<TaskExecutionEntity> findByStatusOrderByCreatedAtDesc(TaskExecutionStatus status);
}
