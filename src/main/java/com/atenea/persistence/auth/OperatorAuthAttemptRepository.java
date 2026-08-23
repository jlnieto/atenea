package com.atenea.persistence.auth;

import com.atenea.auth.recovery.AuthAttemptScope;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatorAuthAttemptRepository
        extends JpaRepository<OperatorAuthAttemptEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select attempt from OperatorAuthAttemptEntity attempt "
            + "where attempt.operator.id = :operatorId and attempt.scope = :scope")
    Optional<OperatorAuthAttemptEntity> findByOperatorIdAndScopeForUpdate(
            @Param("operatorId") Long operatorId,
            @Param("scope") AuthAttemptScope scope);
}
