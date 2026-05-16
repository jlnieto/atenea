package com.atenea.persistence.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedServiceRepository extends JpaRepository<ManagedServiceEntity, Long> {

    List<ManagedServiceEntity> findByHostIdAndActiveTrueOrderByNameAsc(Long hostId);

    Optional<ManagedServiceEntity> findFirstByHostIdAndNameIgnoreCaseAndActiveTrue(Long hostId, String name);

    Optional<ManagedServiceEntity> findFirstByHostIdAndServiceTypeAndActiveTrueOrderByNameAsc(
            Long hostId,
            ManagedServiceType serviceType);
}
