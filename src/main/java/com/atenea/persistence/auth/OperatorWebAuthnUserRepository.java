package com.atenea.persistence.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorWebAuthnUserRepository
        extends JpaRepository<OperatorWebAuthnUserEntity, Long> {

    Optional<OperatorWebAuthnUserEntity> findByUserHandle(byte[] userHandle);
}
