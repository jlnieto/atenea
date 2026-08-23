package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorRepository extends JpaRepository<OperatorEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operator from OperatorEntity operator where operator.id = :operatorId")
    Optional<OperatorEntity> findByIdForRecoveryRequest(@Param("operatorId") Long operatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select operator from OperatorEntity operator where operator.id = :operatorId")
    Optional<OperatorEntity> findByIdForUpdate(@Param("operatorId") Long operatorId);

    Optional<OperatorEntity> findByEmailIgnoreCase(String email);
}
