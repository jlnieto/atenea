package com.atenea.persistence.worksession;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionDeliverableRepository extends JpaRepository<SessionDeliverableEntity, Long> {

    @EntityGraph(attributePaths = "session")
    List<SessionDeliverableEntity> findBySessionIdOrderByTypeAscVersionDesc(Long sessionId);

    @EntityGraph(attributePaths = "session")
    List<SessionDeliverableEntity> findBySessionIdAndApprovedTrueOrderByTypeAscVersionDesc(Long sessionId);

    @EntityGraph(attributePaths = "session")
    List<SessionDeliverableEntity> findBySessionIdAndTypeOrderByVersionDesc(Long sessionId, SessionDeliverableType type);

    @EntityGraph(attributePaths = "session")
    java.util.Optional<SessionDeliverableEntity> findFirstBySessionIdAndTypeOrderByVersionDesc(
            Long sessionId,
            SessionDeliverableType type
    );

    @EntityGraph(attributePaths = "session")
    Optional<SessionDeliverableEntity> findByIdAndSessionId(Long id, Long sessionId);
}
