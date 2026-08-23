package com.atenea.persistence.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorSecurityEventRepository
        extends JpaRepository<OperatorSecurityEventEntity, UUID> {
}
