package com.atenea.persistence.worksession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@IdClass(SessionTurnAttachmentId.class)
@Table(name = "session_turn_attachment")
public class SessionTurnAttachmentEntity {

    @Column(name = "work_session_id", nullable = false, updatable = false)
    private Long workSessionId;

    @Id
    @Column(name = "session_turn_id", nullable = false, updatable = false)
    private Long sessionTurnId;

    @Id
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;

    @Column(nullable = false, updatable = false)
    private short position;

    public Long getWorkSessionId() {
        return workSessionId;
    }

    public Long getSessionTurnId() {
        return sessionTurnId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    public short getPosition() {
        return position;
    }
}
