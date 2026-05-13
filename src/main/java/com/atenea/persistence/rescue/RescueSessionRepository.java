package com.atenea.persistence.rescue;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RescueSessionRepository extends JpaRepository<RescueSessionEntity, Long> {

    @EntityGraph(attributePaths = "project")
    Optional<RescueSessionEntity> findWithProjectById(Long id);

    @EntityGraph(attributePaths = "project")
    Optional<RescueSessionEntity> findFirstByProjectIdAndStatusInOrderByLastActivityAtDesc(
            Long projectId,
            Collection<RescueSessionStatus> statuses);
}
