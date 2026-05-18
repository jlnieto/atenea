package com.atenea.persistence.database;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectDatabaseRefreshRunRepository extends JpaRepository<ProjectDatabaseRefreshRunEntity, Long> {

    List<ProjectDatabaseRefreshRunEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
