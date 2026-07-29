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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_session_preview")
public class WorkSessionPreviewEntity {

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

    @Column(name = "worker_id", nullable = false, length = 80)
    private String workerId;

    @Column(name = "allocation_identity", nullable = false, length = 200)
    private String allocationIdentity;

    @Column(name = "allocation_fingerprint", nullable = false, length = 64)
    private String allocationFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PreviewState state;

    @Version
    @Column(name = "lifecycle_revision", nullable = false)
    private long lifecycleRevision;

    @Column(name = "localhost_compatible", nullable = false)
    private boolean localhostCompatible;

    @Column(name = "private_url", length = 500)
    private String privateUrl;

    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    @Column(name = "hard_expires_at", nullable = false)
    private Instant hardExpiresAt;

    @Column(name = "audit_retain_until", nullable = false)
    private Instant auditRetainUntil;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "next_action", length = 500)
    private String nextAction;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public WorkSessionEntity getWorkSession() { return workSession; }
    public void setWorkSession(WorkSessionEntity workSession) { this.workSession = workSession; }
    public ProjectEntity getProject() { return project; }
    public void setProject(ProjectEntity project) { this.project = project; }
    public AgentRunEntity getAgentRun() { return agentRun; }
    public void setAgentRun(AgentRunEntity agentRun) { this.agentRun = agentRun; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getAllocationIdentity() { return allocationIdentity; }
    public void setAllocationIdentity(String allocationIdentity) { this.allocationIdentity = allocationIdentity; }
    public String getAllocationFingerprint() { return allocationFingerprint; }
    public void setAllocationFingerprint(String allocationFingerprint) { this.allocationFingerprint = allocationFingerprint; }
    public PreviewState getState() { return state; }
    public void setState(PreviewState state) { this.state = state; }
    public long getLifecycleRevision() { return lifecycleRevision; }
    public void setLifecycleRevision(long lifecycleRevision) { this.lifecycleRevision = lifecycleRevision; }
    public boolean isLocalhostCompatible() { return localhostCompatible; }
    public void setLocalhostCompatible(boolean localhostCompatible) { this.localhostCompatible = localhostCompatible; }
    public String getPrivateUrl() { return privateUrl; }
    public void setPrivateUrl(String privateUrl) { this.privateUrl = privateUrl; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Instant getHardExpiresAt() { return hardExpiresAt; }
    public void setHardExpiresAt(Instant hardExpiresAt) { this.hardExpiresAt = hardExpiresAt; }
    public Instant getAuditRetainUntil() { return auditRetainUntil; }
    public void setAuditRetainUntil(Instant auditRetainUntil) { this.auditRetainUntil = auditRetainUntil; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getNextAction() { return nextAction; }
    public void setNextAction(String nextAction) { this.nextAction = nextAction; }
    public Instant getReadyAt() { return readyAt; }
    public void setReadyAt(Instant readyAt) { this.readyAt = readyAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public void setStoppedAt(Instant stoppedAt) { this.stoppedAt = stoppedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
