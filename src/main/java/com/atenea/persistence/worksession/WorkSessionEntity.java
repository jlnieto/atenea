package com.atenea.persistence.worksession;

import com.atenea.persistence.project.ProjectEntity;
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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_session")
public class WorkSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkSessionStatus status;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "base_branch", nullable = false, length = 120)
    private String baseBranch;

    @Column(name = "workspace_branch", length = 180)
    private String workspaceBranch;

    @Column(name = "external_thread_id", length = 100)
    private String externalThreadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_target", nullable = false, length = 16)
    private ExecutionTarget executionTarget = ExecutionTarget.LOCAL;

    @Column(name = "selected_worker_id", length = 80)
    private String selectedWorkerId;

    @Column(name = "workspace_identity", nullable = false, length = 200)
    private String workspaceIdentity;

    @Column(name = "remote_session_id")
    private UUID remoteSessionId;

    @Column(name = "remote_workload_kind", length = 80)
    private String remoteWorkloadKind;

    @Column(name = "attachment_policy_revision", length = 80)
    private String attachmentPolicyRevision;

    @Column(name = "default_codex_model_id", length = 80)
    private String defaultCodexModelId;

    @Convert(converter = CodexReasoningEffortConverter.class)
    @Column(name = "default_codex_reasoning_effort", length = 16)
    private CodexReasoningEffort defaultCodexReasoningEffort;

    @Column(name = "canonical_source_ref", length = 220)
    private String canonicalSourceRef;

    @Column(name = "canonical_source_commit", length = 64)
    private String canonicalSourceCommit;

    @Column(name = "canonical_source_observation_sha256", length = 64)
    private String canonicalSourceObservationSha256;

    @Column(name = "canonical_source_observed_at")
    private Instant canonicalSourceObservedAt;

    @Column(name = "draft_fingerprint_sha256", length = 64)
    private String draftFingerprintSha256;

    @Column(name = "draft_retained_head", length = 64)
    private String draftRetainedHead;

    @Column(name = "draft_staged_change_count")
    private Integer draftStagedChangeCount;

    @Column(name = "draft_unstaged_change_count")
    private Integer draftUnstagedChangeCount;

    @Column(name = "draft_untracked_change_count")
    private Integer draftUntrackedChangeCount;

    @Column(name = "draft_blocked_at")
    private Instant draftBlockedAt;

    @Column(name = "replacement_work_session_id")
    private Long replacementWorkSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "acceptance_state", nullable = false, length = 32)
    private WorkSessionAcceptanceState acceptanceState = WorkSessionAcceptanceState.DRAFT;

    @Column(name = "source_tree_fingerprint_sha256", length = 64)
    private String sourceTreeFingerprintSha256;

    @Column(name = "source_tree_observed_at")
    private Instant sourceTreeObservedAt;

    @Column(name = "validation_projection_sha256", length = 64)
    private String validationProjectionSha256;

    @Column(name = "validation_definition_revision", length = 80)
    private String validationDefinitionRevision;

    @Column(name = "acceptance_blocked_check", length = 80)
    private String acceptanceBlockedCheck;

    @Column(name = "acceptance_next_action", length = 240)
    private String acceptanceNextAction;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "integration_ready_at")
    private Instant integrationReadyAt;

    @Column(name = "pull_request_url", length = 500)
    private String pullRequestUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "pull_request_status", nullable = false, length = 32)
    private WorkSessionPullRequestStatus pullRequestStatus;

    @Column(name = "final_commit_sha", length = 64)
    private String finalCommitSha;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "close_blocked_state", length = 120)
    private String closeBlockedState;

    @Column(name = "close_blocked_reason")
    private String closeBlockedReason;

    @Column(name = "close_blocked_action")
    private String closeBlockedAction;

    @Column(name = "close_retryable", nullable = false)
    private boolean closeRetryable;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public WorkSessionStatus getStatus() {
        return status;
    }

    public void setStatus(WorkSessionStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getWorkspaceBranch() {
        return workspaceBranch;
    }

    public void setWorkspaceBranch(String workspaceBranch) {
        this.workspaceBranch = workspaceBranch;
    }

    public String getExternalThreadId() {
        return externalThreadId;
    }

    public void setExternalThreadId(String externalThreadId) {
        this.externalThreadId = externalThreadId;
    }

    public ExecutionTarget getExecutionTarget() {
        return executionTarget;
    }

    public void setExecutionTarget(ExecutionTarget executionTarget) {
        this.executionTarget = executionTarget;
    }

    public String getSelectedWorkerId() {
        return selectedWorkerId;
    }

    public void setSelectedWorkerId(String selectedWorkerId) {
        this.selectedWorkerId = selectedWorkerId;
    }

    public String getWorkspaceIdentity() {
        return workspaceIdentity;
    }

    public void setWorkspaceIdentity(String workspaceIdentity) {
        this.workspaceIdentity = workspaceIdentity;
    }

    public UUID getRemoteSessionId() {
        return remoteSessionId;
    }

    public void setRemoteSessionId(UUID remoteSessionId) {
        this.remoteSessionId = remoteSessionId;
    }

    public String getAttachmentPolicyRevision() {
        return attachmentPolicyRevision;
    }

    public void setAttachmentPolicyRevision(String attachmentPolicyRevision) {
        this.attachmentPolicyRevision = attachmentPolicyRevision;
    }

    public String getRemoteWorkloadKind() {
        return remoteWorkloadKind;
    }

    public void setRemoteWorkloadKind(String remoteWorkloadKind) {
        this.remoteWorkloadKind = remoteWorkloadKind;
    }

    public String getDefaultCodexModelId() {
        return defaultCodexModelId;
    }

    public void setDefaultCodexModelId(String defaultCodexModelId) {
        this.defaultCodexModelId = defaultCodexModelId;
    }

    public CodexReasoningEffort getDefaultCodexReasoningEffort() {
        return defaultCodexReasoningEffort;
    }

    public void setDefaultCodexReasoningEffort(CodexReasoningEffort value) {
        this.defaultCodexReasoningEffort = value;
    }

    public String getCanonicalSourceRef() {
        return canonicalSourceRef;
    }

    public void setCanonicalSourceRef(String canonicalSourceRef) {
        this.canonicalSourceRef = canonicalSourceRef;
    }

    public String getCanonicalSourceCommit() {
        return canonicalSourceCommit;
    }

    public void setCanonicalSourceCommit(String canonicalSourceCommit) {
        this.canonicalSourceCommit = canonicalSourceCommit;
    }

    public String getCanonicalSourceObservationSha256() {
        return canonicalSourceObservationSha256;
    }

    public void setCanonicalSourceObservationSha256(String canonicalSourceObservationSha256) {
        this.canonicalSourceObservationSha256 = canonicalSourceObservationSha256;
    }

    public Instant getCanonicalSourceObservedAt() {
        return canonicalSourceObservedAt;
    }

    public void setCanonicalSourceObservedAt(Instant canonicalSourceObservedAt) {
        this.canonicalSourceObservedAt = canonicalSourceObservedAt;
    }

    public String getDraftFingerprintSha256() {
        return draftFingerprintSha256;
    }

    public void setDraftFingerprintSha256(String draftFingerprintSha256) {
        this.draftFingerprintSha256 = draftFingerprintSha256;
    }

    public String getDraftRetainedHead() {
        return draftRetainedHead;
    }

    public void setDraftRetainedHead(String draftRetainedHead) {
        this.draftRetainedHead = draftRetainedHead;
    }

    public Integer getDraftStagedChangeCount() {
        return draftStagedChangeCount;
    }

    public void setDraftStagedChangeCount(Integer draftStagedChangeCount) {
        this.draftStagedChangeCount = draftStagedChangeCount;
    }

    public Integer getDraftUnstagedChangeCount() {
        return draftUnstagedChangeCount;
    }

    public void setDraftUnstagedChangeCount(Integer draftUnstagedChangeCount) {
        this.draftUnstagedChangeCount = draftUnstagedChangeCount;
    }

    public Integer getDraftUntrackedChangeCount() {
        return draftUntrackedChangeCount;
    }

    public void setDraftUntrackedChangeCount(Integer draftUntrackedChangeCount) {
        this.draftUntrackedChangeCount = draftUntrackedChangeCount;
    }

    public Instant getDraftBlockedAt() {
        return draftBlockedAt;
    }

    public void setDraftBlockedAt(Instant draftBlockedAt) {
        this.draftBlockedAt = draftBlockedAt;
    }

    public Long getReplacementWorkSessionId() {
        return replacementWorkSessionId;
    }

    public void setReplacementWorkSessionId(Long replacementWorkSessionId) {
        this.replacementWorkSessionId = replacementWorkSessionId;
    }

    public WorkSessionAcceptanceState getAcceptanceState() {
        return acceptanceState;
    }

    public void setAcceptanceState(WorkSessionAcceptanceState acceptanceState) {
        this.acceptanceState = acceptanceState;
    }

    public String getSourceTreeFingerprintSha256() {
        return sourceTreeFingerprintSha256;
    }

    public void setSourceTreeFingerprintSha256(String sourceTreeFingerprintSha256) {
        this.sourceTreeFingerprintSha256 = sourceTreeFingerprintSha256;
    }

    public Instant getSourceTreeObservedAt() {
        return sourceTreeObservedAt;
    }

    public void setSourceTreeObservedAt(Instant sourceTreeObservedAt) {
        this.sourceTreeObservedAt = sourceTreeObservedAt;
    }

    public String getValidationProjectionSha256() {
        return validationProjectionSha256;
    }

    public void setValidationProjectionSha256(String validationProjectionSha256) {
        this.validationProjectionSha256 = validationProjectionSha256;
    }

    public String getValidationDefinitionRevision() {
        return validationDefinitionRevision;
    }

    public void setValidationDefinitionRevision(String validationDefinitionRevision) {
        this.validationDefinitionRevision = validationDefinitionRevision;
    }

    public String getAcceptanceBlockedCheck() {
        return acceptanceBlockedCheck;
    }

    public void setAcceptanceBlockedCheck(String acceptanceBlockedCheck) {
        this.acceptanceBlockedCheck = acceptanceBlockedCheck;
    }

    public String getAcceptanceNextAction() {
        return acceptanceNextAction;
    }

    public void setAcceptanceNextAction(String acceptanceNextAction) {
        this.acceptanceNextAction = acceptanceNextAction;
    }

    public Instant getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(Instant validatedAt) {
        this.validatedAt = validatedAt;
    }

    public Instant getIntegrationReadyAt() {
        return integrationReadyAt;
    }

    public void setIntegrationReadyAt(Instant integrationReadyAt) {
        this.integrationReadyAt = integrationReadyAt;
    }

    public String getPullRequestUrl() {
        return pullRequestUrl;
    }

    public void setPullRequestUrl(String pullRequestUrl) {
        this.pullRequestUrl = pullRequestUrl;
    }

    public WorkSessionPullRequestStatus getPullRequestStatus() {
        return pullRequestStatus;
    }

    public void setPullRequestStatus(WorkSessionPullRequestStatus pullRequestStatus) {
        this.pullRequestStatus = pullRequestStatus;
    }

    public String getFinalCommitSha() {
        return finalCommitSha;
    }

    public void setFinalCommitSha(String finalCommitSha) {
        this.finalCommitSha = finalCommitSha;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(Instant lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getCloseBlockedState() {
        return closeBlockedState;
    }

    public void setCloseBlockedState(String closeBlockedState) {
        this.closeBlockedState = closeBlockedState;
    }

    public String getCloseBlockedReason() {
        return closeBlockedReason;
    }

    public void setCloseBlockedReason(String closeBlockedReason) {
        this.closeBlockedReason = closeBlockedReason;
    }

    public String getCloseBlockedAction() {
        return closeBlockedAction;
    }

    public void setCloseBlockedAction(String closeBlockedAction) {
        this.closeBlockedAction = closeBlockedAction;
    }

    public boolean isCloseRetryable() {
        return closeRetryable;
    }

    public void setCloseRetryable(boolean closeRetryable) {
        this.closeRetryable = closeRetryable;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
