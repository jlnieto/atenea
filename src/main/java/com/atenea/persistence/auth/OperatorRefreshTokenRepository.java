package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorRefreshTokenRepository extends JpaRepository<OperatorRefreshTokenEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from OperatorRefreshTokenEntity token where token.tokenHash = :tokenHash")
    Optional<OperatorRefreshTokenEntity> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OperatorRefreshTokenEntity token
            set token.revokedAt = :revokedAt,
                token.revocationReason = :reason,
                token.updatedAt = :revokedAt
            where token.sessionFamily.id = :familyId
              and token.revokedAt is null
            """)
    int revokeActiveFamilyTokens(
            @Param("familyId") UUID familyId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OperatorRefreshTokenEntity token
            set token.revokedAt = :revokedAt,
                token.revocationReason = :reason,
                token.updatedAt = :revokedAt
            where token.operator.id = :operatorId
              and token.sessionFamily is null
              and token.revokedAt is null
            """)
    int revokeActiveLegacyTokens(
            @Param("operatorId") Long operatorId,
            @Param("revokedAt") Instant revokedAt,
            @Param("reason") String reason);

    long deleteByExpiresAtBeforeAndSessionFamilyIsNull(Instant instant);
}
