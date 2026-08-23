package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorRecoveryCodeRepository
        extends JpaRepository<OperatorRecoveryCodeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from OperatorRecoveryCodeEntity code where code.codeHmac = :codeHmac")
    Optional<OperatorRecoveryCodeEntity> findByCodeHmacForUpdate(
            @Param("codeHmac") byte[] codeHmac);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from OperatorRecoveryCodeEntity code "
            + "where code.operator.id = :operatorId order by code.id")
    List<OperatorRecoveryCodeEntity> findAllByOperatorIdForUpdate(
            @Param("operatorId") Long operatorId);

    List<OperatorRecoveryCodeEntity> findAllByOperatorIdOrderById(Long operatorId);
}
