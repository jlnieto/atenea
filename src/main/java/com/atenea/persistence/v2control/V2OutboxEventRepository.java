package com.atenea.persistence.v2control;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface V2OutboxEventRepository extends JpaRepository<V2OutboxEventEntity, UUID> {
}
