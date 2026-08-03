package com.atenea.persistence.worksession;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SessionTurnAttachmentRepository
        extends Repository<SessionTurnAttachmentEntity, SessionTurnAttachmentId> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO session_turn_attachment (
                work_session_id,
                session_turn_id,
                attachment_id,
                position
            ) VALUES (
                :workSessionId,
                :sessionTurnId,
                :attachmentId,
                :position
            )
            """, nativeQuery = true)
    int insert(
            @Param("workSessionId") Long workSessionId,
            @Param("sessionTurnId") Long sessionTurnId,
            @Param("attachmentId") UUID attachmentId,
            @Param("position") short position
    );

    List<SessionTurnAttachmentEntity> findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(
            Long workSessionId,
            Long sessionTurnId
    );

    List<SessionTurnAttachmentEntity>
            findByWorkSessionIdAndSessionTurnIdInOrderBySessionTurnIdAscPositionAsc(
                    Long workSessionId,
                    Collection<Long> sessionTurnIds
            );
}
