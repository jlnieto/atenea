package com.atenea.persistence.worksession;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerNodeRepository extends JpaRepository<WorkerNodeEntity, String> {
}
