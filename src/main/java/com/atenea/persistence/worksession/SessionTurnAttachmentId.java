package com.atenea.persistence.worksession;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public final class SessionTurnAttachmentId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long sessionTurnId;
    private UUID attachmentId;

    public SessionTurnAttachmentId() {
    }

    public SessionTurnAttachmentId(Long sessionTurnId, UUID attachmentId) {
        this.sessionTurnId = sessionTurnId;
        this.attachmentId = attachmentId;
    }

    public Long getSessionTurnId() {
        return sessionTurnId;
    }

    public UUID getAttachmentId() {
        return attachmentId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionTurnAttachmentId that)) {
            return false;
        }
        return Objects.equals(sessionTurnId, that.sessionTurnId)
                && Objects.equals(attachmentId, that.attachmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionTurnId, attachmentId);
    }
}
