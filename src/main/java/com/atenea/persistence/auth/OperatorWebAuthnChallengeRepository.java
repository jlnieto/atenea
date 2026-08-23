package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorWebAuthnChallengeRepository
        extends JpaRepository<OperatorWebAuthnChallengeEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from OperatorWebAuthnChallengeEntity challenge "
            + "left join fetch challenge.operator left join fetch challenge.sessionFamily "
            + "where challenge.id = :id")
    Optional<OperatorWebAuthnChallengeEntity> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("update OperatorWebAuthnChallengeEntity challenge "
            + "set challenge.consumedAt = :consumedAt, "
            + "challenge.rowVersion = challenge.rowVersion + 1 "
            + "where challenge.operator.id = :operatorId and challenge.consumedAt is null")
    int consumeActiveByOperatorId(
            @Param("operatorId") Long operatorId,
            @Param("consumedAt") java.time.Instant consumedAt);
}
