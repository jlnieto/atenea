package com.atenea.persistence.auth;

import com.atenea.auth.recovery.TotpFactorState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorTotpFactorRepository
        extends JpaRepository<OperatorTotpFactorEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select factor from OperatorTotpFactorEntity factor where factor.enrollmentId = :enrollmentId")
    Optional<OperatorTotpFactorEntity> findByEnrollmentIdForUpdate(
            @Param("enrollmentId") UUID enrollmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select factor from OperatorTotpFactorEntity factor "
            + "where factor.operator.id = :operatorId and factor.state = :state order by factor.id")
    List<OperatorTotpFactorEntity> findAllByOperatorIdAndStateForUpdate(
            @Param("operatorId") Long operatorId,
            @Param("state") TotpFactorState state);

    List<OperatorTotpFactorEntity> findAllByOperatorIdAndStateOrderById(
            Long operatorId,
            TotpFactorState state);
}
