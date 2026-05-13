package com.atenea.persistence.rescue;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RescueSessionTurnRepository extends JpaRepository<RescueSessionTurnEntity, Long> {

    List<RescueSessionTurnEntity> findByRescueSessionIdOrderByCreatedAtAsc(Long rescueSessionId);
}
