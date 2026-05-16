package com.atenea.persistence.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedHostRepository extends JpaRepository<ManagedHostEntity, Long> {

    List<ManagedHostEntity> findByActiveTrueOrderByNameAsc();

    Optional<ManagedHostEntity> findByIdAndActiveTrue(Long id);
}
