package com.atenea.persistence.notification;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_event")
public class NotificationEventEntity {
    @Id private UUID id;
    @Column(name = "deduplication_sha256", nullable = false, unique = true, length = 64, updatable = false)
    private String deduplicationSha256;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32, updatable = false)
    private NotificationCategory category;
    @Column(name = "template_version", nullable = false, length = 40, updatable = false)
    private String templateVersion;
    @Column(name = "deep_link_kind", nullable = false, length = 40, updatable = false)
    private String deepLinkKind;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private WorkSessionEntity session;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "agent_run_id", nullable = false, updatable = false)
    private AgentRunEntity agentRun;
    @Column(name = "source_revision", nullable = false, updatable = false) private long sourceRevision;
    @Column(name = "safe_title", nullable = false, length = 120, updatable = false) private String safeTitle;
    @Column(name = "safe_body", nullable = false, length = 190, updatable = false) private String safeBody;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public UUID getId() { return id; } public void setId(UUID value) { id = value; }
    public String getDeduplicationSha256() { return deduplicationSha256; } public void setDeduplicationSha256(String value) { deduplicationSha256 = value; }
    public NotificationCategory getCategory() { return category; } public void setCategory(NotificationCategory value) { category = value; }
    public String getTemplateVersion() { return templateVersion; } public void setTemplateVersion(String value) { templateVersion = value; }
    public String getDeepLinkKind() { return deepLinkKind; } public void setDeepLinkKind(String value) { deepLinkKind = value; }
    public WorkSessionEntity getSession() { return session; } public void setSession(WorkSessionEntity value) { session = value; }
    public AgentRunEntity getAgentRun() { return agentRun; } public void setAgentRun(AgentRunEntity value) { agentRun = value; }
    public long getSourceRevision() { return sourceRevision; } public void setSourceRevision(long value) { sourceRevision = value; }
    public String getSafeTitle() { return safeTitle; } public void setSafeTitle(String value) { safeTitle = value; }
    public String getSafeBody() { return safeBody; } public void setSafeBody(String value) { safeBody = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
}
