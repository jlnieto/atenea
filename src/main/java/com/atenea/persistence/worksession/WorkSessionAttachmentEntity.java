package com.atenea.persistence.worksession;

import com.atenea.persistence.project.ProjectEntity;
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
@Table(name = "work_session_attachment")
public class WorkSessionAttachmentEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_session_id", nullable = false)
    private WorkSessionEntity workSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_id")
    private AgentRunEntity agentRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttachmentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AttachmentKind kind;

    @Column(name = "original_filename", nullable = false, length = 180)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "retention_class", nullable = false, length = 16)
    private AttachmentRetentionClass retentionClass;

    @Column(name = "retain_until", nullable = false)
    private Instant retainUntil;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "worker_id", nullable = false, length = 80)
    private String workerId;

    @Column(name = "storage_identity", nullable = false, length = 300)
    private String storageIdentity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "indexed_at", nullable = false, updatable = false)
    private Instant indexedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public WorkSessionEntity getWorkSession() {
        return workSession;
    }

    public void setWorkSession(WorkSessionEntity workSession) {
        this.workSession = workSession;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public AgentRunEntity getAgentRun() {
        return agentRun;
    }

    public void setAgentRun(AgentRunEntity agentRun) {
        this.agentRun = agentRun;
    }

    public AttachmentSource getSource() {
        return source;
    }

    public void setSource(AttachmentSource source) {
        this.source = source;
    }

    public AttachmentKind getKind() {
        return kind;
    }

    public void setKind(AttachmentKind kind) {
        this.kind = kind;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public AttachmentRetentionClass getRetentionClass() {
        return retentionClass;
    }

    public void setRetentionClass(AttachmentRetentionClass retentionClass) {
        this.retentionClass = retentionClass;
    }

    public Instant getRetainUntil() {
        return retainUntil;
    }

    public void setRetainUntil(Instant retainUntil) {
        this.retainUntil = retainUntil;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getStorageIdentity() {
        return storageIdentity;
    }

    public void setStorageIdentity(String storageIdentity) {
        this.storageIdentity = storageIdentity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(Instant indexedAt) {
        this.indexedAt = indexedAt;
    }
}
