package com.atenea.persistence.worksession;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_run")
public class AgentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private WorkSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "origin_turn_id", nullable = false)
    private SessionTurnEntity originTurn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_turn_id")
    private SessionTurnEntity resultTurn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "process_outcome", length = 16)
    private AgentRunProcessOutcome processOutcome;

    @Column(name = "target_repo_path", nullable = false, length = 500)
    private String targetRepoPath;

    @Column(name = "external_turn_id", length = 100)
    private String externalTurnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_target", nullable = false, length = 16)
    private ExecutionTarget executionTarget = ExecutionTarget.LOCAL;

    @Column(name = "selected_worker_id", length = 80)
    private String selectedWorkerId;

    @Column(name = "workspace_identity", nullable = false, length = 200)
    private String workspaceIdentity;

    @Column(name = "remote_session_id")
    private UUID remoteSessionId;

    @Column(name = "workload_kind", length = 80)
    private String workloadKind;

    @Column(name = "project_identity", length = 80)
    private String projectIdentity;

    @Column(name = "repository_url", length = 500)
    private String repositoryUrl;

    @Column(name = "repository_branch", length = 180)
    private String repositoryBranch;

    @Column(name = "repository_commit", length = 64)
    private String repositoryCommit;

    @Column(name = "worker_mirror_commit", length = 64)
    private String workerMirrorCommit;

    @Column(name = "manifest_sha256", length = 64)
    private String manifestSha256;

    @Column(name = "instruction_bundle_revision", length = 80)
    private String instructionBundleRevision;

    @Column(name = "instruction_bundle_sha256", length = 64)
    private String instructionBundleSha256;

    @Column(name = "platform_instruction_sha256", length = 64)
    private String platformInstructionSha256;

    @Column(name = "project_instruction_path", length = 80)
    private String projectInstructionPath;

    @Column(name = "project_instruction_sha256", length = 64)
    private String projectInstructionSha256;

    @Column(name = "codex_model_id", length = 80, updatable = false)
    private String codexModelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "codex_model_source", length = 24, updatable = false)
    private ExecutionProfileSource codexModelSource;

    @Convert(converter = CodexReasoningEffortConverter.class)
    @Column(name = "codex_reasoning_effort", length = 16, updatable = false)
    private CodexReasoningEffort codexReasoningEffort;

    @Enumerated(EnumType.STRING)
    @Column(name = "codex_effort_source", length = 24, updatable = false)
    private ExecutionProfileSource codexEffortSource;

    @Column(name = "codex_catalog_revision", length = 64, updatable = false)
    private String codexCatalogRevision;

    @Column(name = "codex_version", length = 32, updatable = false)
    private String codexVersion;

    @Column(name = "dispatch_id", unique = true)
    private UUID dispatchId;

    @Column(name = "remote_execution_id", length = 100)
    private String remoteExecutionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workload_class", nullable = false, length = 16)
    private WorkloadClass workloadClass = WorkloadClass.NORMAL;

    @Column(name = "lease_generation", nullable = false)
    private long leaseGeneration;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "lifecycle_revision", nullable = false)
    private long lifecycleRevision;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "reconciliation_started_at")
    private Instant reconciliationStartedAt;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public WorkSessionEntity getSession() {
        return session;
    }

    public void setSession(WorkSessionEntity session) {
        this.session = session;
    }

    public SessionTurnEntity getOriginTurn() {
        return originTurn;
    }

    public void setOriginTurn(SessionTurnEntity originTurn) {
        this.originTurn = originTurn;
    }

    public SessionTurnEntity getResultTurn() {
        return resultTurn;
    }

    public void setResultTurn(SessionTurnEntity resultTurn) {
        this.resultTurn = resultTurn;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = status;
        this.processOutcome = AgentRunProcessOutcome.fromStatus(status);
    }

    public AgentRunProcessOutcome getProcessOutcome() {
        return processOutcome;
    }

    public void setProcessOutcome(AgentRunProcessOutcome processOutcome) {
        this.processOutcome = processOutcome;
    }

    public String getTargetRepoPath() {
        return targetRepoPath;
    }

    public void setTargetRepoPath(String targetRepoPath) {
        this.targetRepoPath = targetRepoPath;
    }

    public String getExternalTurnId() {
        return externalTurnId;
    }

    public void setExternalTurnId(String externalTurnId) {
        this.externalTurnId = externalTurnId;
    }

    public ExecutionTarget getExecutionTarget() { return executionTarget; }
    public void setExecutionTarget(ExecutionTarget executionTarget) { this.executionTarget = executionTarget; }
    public String getSelectedWorkerId() { return selectedWorkerId; }
    public void setSelectedWorkerId(String selectedWorkerId) { this.selectedWorkerId = selectedWorkerId; }
    public String getWorkspaceIdentity() { return workspaceIdentity; }
    public void setWorkspaceIdentity(String workspaceIdentity) { this.workspaceIdentity = workspaceIdentity; }
    public UUID getRemoteSessionId() { return remoteSessionId; }
    public void setRemoteSessionId(UUID remoteSessionId) { this.remoteSessionId = remoteSessionId; }
    public String getWorkloadKind() { return workloadKind; }
    public void setWorkloadKind(String workloadKind) { this.workloadKind = workloadKind; }
    public String getProjectIdentity() { return projectIdentity; }
    public void setProjectIdentity(String projectIdentity) { this.projectIdentity = projectIdentity; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
    public String getRepositoryBranch() { return repositoryBranch; }
    public void setRepositoryBranch(String repositoryBranch) { this.repositoryBranch = repositoryBranch; }
    public String getRepositoryCommit() { return repositoryCommit; }
    public void setRepositoryCommit(String repositoryCommit) { this.repositoryCommit = repositoryCommit; }
    public String getWorkerMirrorCommit() { return workerMirrorCommit; }
    public void setWorkerMirrorCommit(String workerMirrorCommit) { this.workerMirrorCommit = workerMirrorCommit; }
    public String getManifestSha256() { return manifestSha256; }
    public void setManifestSha256(String manifestSha256) { this.manifestSha256 = manifestSha256; }
    public String getInstructionBundleRevision() { return instructionBundleRevision; }
    public void setInstructionBundleRevision(String value) { this.instructionBundleRevision = value; }
    public String getInstructionBundleSha256() { return instructionBundleSha256; }
    public void setInstructionBundleSha256(String value) { this.instructionBundleSha256 = value; }
    public String getPlatformInstructionSha256() { return platformInstructionSha256; }
    public void setPlatformInstructionSha256(String value) { this.platformInstructionSha256 = value; }
    public String getProjectInstructionPath() { return projectInstructionPath; }
    public void setProjectInstructionPath(String value) { this.projectInstructionPath = value; }
    public String getProjectInstructionSha256() { return projectInstructionSha256; }
    public void setProjectInstructionSha256(String value) { this.projectInstructionSha256 = value; }
    public String getCodexModelId() { return codexModelId; }
    public void setCodexModelId(String codexModelId) { this.codexModelId = codexModelId; }
    public ExecutionProfileSource getCodexModelSource() { return codexModelSource; }
    public void setCodexModelSource(ExecutionProfileSource codexModelSource) { this.codexModelSource = codexModelSource; }
    public CodexReasoningEffort getCodexReasoningEffort() { return codexReasoningEffort; }
    public void setCodexReasoningEffort(CodexReasoningEffort codexReasoningEffort) { this.codexReasoningEffort = codexReasoningEffort; }
    public ExecutionProfileSource getCodexEffortSource() { return codexEffortSource; }
    public void setCodexEffortSource(ExecutionProfileSource codexEffortSource) { this.codexEffortSource = codexEffortSource; }
    public String getCodexCatalogRevision() { return codexCatalogRevision; }
    public void setCodexCatalogRevision(String codexCatalogRevision) { this.codexCatalogRevision = codexCatalogRevision; }
    public String getCodexVersion() { return codexVersion; }
    public void setCodexVersion(String codexVersion) { this.codexVersion = codexVersion; }
    public UUID getDispatchId() { return dispatchId; }
    public void setDispatchId(UUID dispatchId) { this.dispatchId = dispatchId; }
    public String getRemoteExecutionId() { return remoteExecutionId; }
    public void setRemoteExecutionId(String remoteExecutionId) { this.remoteExecutionId = remoteExecutionId; }
    public WorkloadClass getWorkloadClass() { return workloadClass; }
    public void setWorkloadClass(WorkloadClass workloadClass) { this.workloadClass = workloadClass; }
    public long getLeaseGeneration() { return leaseGeneration; }
    public void setLeaseGeneration(long leaseGeneration) { this.leaseGeneration = leaseGeneration; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public long getLifecycleRevision() { return lifecycleRevision; }
    public void setLifecycleRevision(long lifecycleRevision) { this.lifecycleRevision = lifecycleRevision; }
    public Instant getQueuedAt() { return queuedAt; }
    public void setQueuedAt(Instant queuedAt) { this.queuedAt = queuedAt; }
    public Instant getCancellationRequestedAt() { return cancellationRequestedAt; }
    public void setCancellationRequestedAt(Instant cancellationRequestedAt) { this.cancellationRequestedAt = cancellationRequestedAt; }
    public Instant getReconciliationStartedAt() { return reconciliationStartedAt; }
    public void setReconciliationStartedAt(Instant reconciliationStartedAt) { this.reconciliationStartedAt = reconciliationStartedAt; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public long getLockVersion() { return lockVersion; }
    public void setLockVersion(long lockVersion) { this.lockVersion = lockVersion; }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
