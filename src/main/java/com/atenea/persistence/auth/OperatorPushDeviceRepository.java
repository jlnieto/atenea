package com.atenea.persistence.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorPushDeviceRepository extends JpaRepository<OperatorPushDeviceEntity, Long> {

    @EntityGraph(attributePaths = "operator")
    Optional<OperatorPushDeviceEntity> findByExpoPushToken(String expoPushToken);

    List<OperatorPushDeviceEntity> findByOperatorIdOrderByUpdatedAtDesc(Long operatorId);

    List<OperatorPushDeviceEntity> findByActiveTrueOrderByUpdatedAtDesc();
}
