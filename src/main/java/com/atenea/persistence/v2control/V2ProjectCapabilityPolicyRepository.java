package com.atenea.persistence.v2control;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface V2ProjectCapabilityPolicyRepository
        extends JpaRepository<V2ProjectCapabilityPolicyEntity, Long> {

    Optional<V2ProjectCapabilityPolicyEntity> findByProjectIdAndCapability(
            Long projectId,
            String capability);
}
