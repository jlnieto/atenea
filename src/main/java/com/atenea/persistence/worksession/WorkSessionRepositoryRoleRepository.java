package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkSessionRepositoryRoleRepository
        extends JpaRepository<WorkSessionRepositoryRoleEntity, UUID> {
    List<WorkSessionRepositoryRoleEntity> findByWorkSessionIdOrderByRoleAsc(Long workSessionId);
    Optional<WorkSessionRepositoryRoleEntity> findByWorkSessionIdAndRole(
            Long workSessionId, RepositoryRoleKind role);
}
