package com.atenea.persistence.operations;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationsActionRunRepository extends JpaRepository<OperationsActionRunEntity, Long> {

    @EntityGraph(attributePaths = {"host", "service", "incident"})
    List<OperationsActionRunEntity> findTop20ByHostIdOrderByCreatedAtDesc(Long hostId);
}
