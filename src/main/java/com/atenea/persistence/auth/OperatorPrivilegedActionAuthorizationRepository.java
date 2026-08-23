package com.atenea.persistence.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorPrivilegedActionAuthorizationRepository
        extends JpaRepository<OperatorPrivilegedActionAuthorizationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select authorization from OperatorPrivilegedActionAuthorizationEntity authorization "
            + "join fetch authorization.operator join fetch authorization.sessionFamily "
            + "where authorization.authorizationDigest = :digest")
    Optional<OperatorPrivilegedActionAuthorizationEntity> findByDigestForUpdate(
            @Param("digest") byte[] digest);
}
