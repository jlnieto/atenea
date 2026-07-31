package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationOperationRepository extends JpaRepository<ValidationOperationEntity, UUID> {

    @EntityGraph(attributePaths = {"workSession", "workSession.project"})
    Optional<ValidationOperationEntity> findByIdentitySha256(String identitySha256);

    @EntityGraph(attributePaths = {"workSession", "workSession.project"})
    List<ValidationOperationEntity> findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByOperationAsc(
            Long workSessionId,
            String sourceTreeFingerprintSha256);
}
