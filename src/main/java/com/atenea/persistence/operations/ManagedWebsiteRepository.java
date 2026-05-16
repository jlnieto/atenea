package com.atenea.persistence.operations;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedWebsiteRepository extends JpaRepository<ManagedWebsiteEntity, Long> {

    List<ManagedWebsiteEntity> findByHostIdAndActiveTrueOrderByNameAsc(Long hostId);
}
