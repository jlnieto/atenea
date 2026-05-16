package com.atenea.persistence.worksession;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface SessionTurnRepository extends JpaRepository<SessionTurnEntity, Long> {

    @EntityGraph(attributePaths = {"session", "session.project"})
    List<SessionTurnEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    long countBySessionIdAndInternalFalse(Long sessionId);

    @EntityGraph(attributePaths = {"session", "session.project"})
    List<SessionTurnEntity> findBySessionIdAndInternalFalseOrderByCreatedAtAsc(Long sessionId);

    @EntityGraph(attributePaths = {"session", "session.project"})
    List<SessionTurnEntity> findBySessionIdAndInternalFalseOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    @EntityGraph(attributePaths = {"session", "session.project"})
    List<SessionTurnEntity> findBySessionIdAndInternalFalseAndIdLessThanOrderByCreatedAtDesc(
            Long sessionId,
            Long id,
            Pageable pageable
    );
}
