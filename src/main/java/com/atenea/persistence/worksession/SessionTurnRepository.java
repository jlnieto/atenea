package com.atenea.persistence.worksession;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionTurnRepository extends JpaRepository<SessionTurnEntity, Long> {

    @EntityGraph(attributePaths = {"session", "session.project"})
    List<SessionTurnEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
