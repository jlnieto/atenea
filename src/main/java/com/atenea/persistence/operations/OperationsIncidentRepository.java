package com.atenea.persistence.operations;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationsIncidentRepository extends JpaRepository<OperationsIncidentEntity, Long> {

    @EntityGraph(attributePaths = {"host", "service"})
    List<OperationsIncidentEntity> findByStatusInOrderByLastActivityAtDesc(Collection<OperationsIncidentStatus> statuses);

    @EntityGraph(attributePaths = {"host", "service"})
    Optional<OperationsIncidentEntity> findFirstByHostIdAndServiceIdAndStatusInOrderByLastActivityAtDesc(
            Long hostId,
            Long serviceId,
            Collection<OperationsIncidentStatus> statuses);
}
