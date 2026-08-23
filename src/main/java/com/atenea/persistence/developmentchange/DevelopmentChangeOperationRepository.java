package com.atenea.persistence.developmentchange;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DevelopmentChangeOperationRepository
        extends JpaRepository<DevelopmentChangeOperationEntity, Long> {

    @EntityGraph(attributePaths = {"project", "developmentChange", "workSession"})
    Optional<DevelopmentChangeOperationEntity>
            findByOperatorIdAndOperationKindAndIdempotencyKey(
                    Long operatorId,
                    DevelopmentChangeOperationKind operationKind,
                    UUID idempotencyKey);
}
