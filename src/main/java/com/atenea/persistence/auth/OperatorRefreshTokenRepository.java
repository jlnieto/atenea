package com.atenea.persistence.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorRefreshTokenRepository extends JpaRepository<OperatorRefreshTokenEntity, Long> {

    @EntityGraph(attributePaths = "operator")
    Optional<OperatorRefreshTokenEntity> findByTokenHash(String tokenHash);

    long deleteByExpiresAtBefore(Instant instant);
}
