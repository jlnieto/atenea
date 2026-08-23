package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorWebAuthnCredentialRepository
        extends JpaRepository<OperatorWebAuthnCredentialEntity, UUID> {

    Optional<OperatorWebAuthnCredentialEntity> findByCredentialId(byte[] credentialId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from OperatorWebAuthnCredentialEntity credential "
            + "join fetch credential.operator where credential.credentialId = :credentialId")
    Optional<OperatorWebAuthnCredentialEntity> findByCredentialIdForUpdate(
            @Param("credentialId") byte[] credentialId);

    List<OperatorWebAuthnCredentialEntity> findAllByOperatorIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(
            Long operatorId);

    @Modifying(flushAutomatically = true)
    @Query("update OperatorWebAuthnCredentialEntity credential "
            + "set credential.revokedAt = :revokedAt, credential.revocationReason = :reason, "
            + "credential.rowVersion = credential.rowVersion + 1 "
            + "where credential.operator.id = :operatorId and credential.revokedAt is null")
    int revokeActiveByOperatorId(
            @Param("operatorId") Long operatorId,
            @Param("revokedAt") java.time.Instant revokedAt,
            @Param("reason") String reason);
}
