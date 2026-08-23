package com.atenea.persistence.developmentchange;

import com.atenea.persistence.project.ProjectEntity;
import jakarta.persistence.Column;
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
@Table(name = "development_change")
public class DevelopmentChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "change_key", nullable = false, updatable = false, unique = true)
    private UUID changeKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DevelopmentChangeStatus status = DevelopmentChangeStatus.OPEN;

    @Column(name = "base_ref", nullable = false, length = 220, updatable = false)
    private String baseRef;

    @Column(name = "base_commit", nullable = false, length = 64, updatable = false)
    private String baseCommit;

    @Column(name = "workspace_branch", nullable = false, length = 180, updatable = false)
    private String workspaceBranch;

    @Column(name = "workspace_identity", nullable = false, length = 200, updatable = false)
    private String workspaceIdentity;

    @Column(name = "selected_worker_id", nullable = false, length = 80, updatable = false)
    private String selectedWorkerId;

    @Column(name = "project_policy_revision", nullable = false, updatable = false)
    private long projectPolicyRevision;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "source_fingerprint_sha256", nullable = false, length = 64)
    private String sourceFingerprintSha256;

    @Column(name = "observed_canonical_commit", length = 64)
    private String observedCanonicalCommit;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_state", nullable = false, length = 24)
    private DevelopmentChangeSourceState sourceState = DevelopmentChangeSourceState.CLEAN;

    @Enumerated(EnumType.STRING)
    @Column(name = "workspace_state", nullable = false, length = 24)
    private DevelopmentChangeWorkspaceState workspaceState =
            DevelopmentChangeWorkspaceState.NOT_PROVISIONED;

    @Column(name = "workspace_operation_revision", nullable = false)
    private long workspaceOperationRevision;

    @Column(name = "workspace_observation_sha256", length = 64)
    private String workspaceObservationSha256;

    @Column(name = "workspace_ownership_fingerprint_sha256", length = 64)
    private String workspaceOwnershipFingerprintSha256;

    @Column(name = "workspace_updated_at")
    private Instant workspaceUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_state", nullable = false, length = 24)
    private DevelopmentChangeProjectionState validationState =
            DevelopmentChangeProjectionState.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_state", nullable = false, length = 24)
    private DevelopmentChangeProjectionState reviewState =
            DevelopmentChangeProjectionState.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_state", nullable = false, length = 24)
    private DevelopmentChangeProjectionState integrationState =
            DevelopmentChangeProjectionState.NOT_STARTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_state", nullable = false, length = 24)
    private DevelopmentChangeProjectionState releaseState =
            DevelopmentChangeProjectionState.NOT_STARTED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public UUID getChangeKey() { return changeKey; }
    public void setChangeKey(UUID value) { changeKey = value; }
    public ProjectEntity getProject() { return project; }
    public void setProject(ProjectEntity value) { project = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public DevelopmentChangeStatus getStatus() { return status; }
    public void setStatus(DevelopmentChangeStatus value) { status = value; }
    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String value) { baseRef = value; }
    public String getBaseCommit() { return baseCommit; }
    public void setBaseCommit(String value) { baseCommit = value; }
    public String getWorkspaceBranch() { return workspaceBranch; }
    public void setWorkspaceBranch(String value) { workspaceBranch = value; }
    public String getWorkspaceIdentity() { return workspaceIdentity; }
    public void setWorkspaceIdentity(String value) { workspaceIdentity = value; }
    public String getSelectedWorkerId() { return selectedWorkerId; }
    public void setSelectedWorkerId(String value) { selectedWorkerId = value; }
    public long getProjectPolicyRevision() { return projectPolicyRevision; }
    public void setProjectPolicyRevision(long value) { projectPolicyRevision = value; }
    public long getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(long value) { sourceRevision = value; }
    public String getSourceFingerprintSha256() { return sourceFingerprintSha256; }
    public void setSourceFingerprintSha256(String value) { sourceFingerprintSha256 = value; }
    public String getObservedCanonicalCommit() { return observedCanonicalCommit; }
    public void setObservedCanonicalCommit(String value) { observedCanonicalCommit = value; }
    public DevelopmentChangeSourceState getSourceState() { return sourceState; }
    public void setSourceState(DevelopmentChangeSourceState value) { sourceState = value; }
    public DevelopmentChangeWorkspaceState getWorkspaceState() { return workspaceState; }
    public void setWorkspaceState(DevelopmentChangeWorkspaceState value) { workspaceState = value; }
    public long getWorkspaceOperationRevision() { return workspaceOperationRevision; }
    public void setWorkspaceOperationRevision(long value) { workspaceOperationRevision = value; }
    public String getWorkspaceObservationSha256() { return workspaceObservationSha256; }
    public void setWorkspaceObservationSha256(String value) { workspaceObservationSha256 = value; }
    public String getWorkspaceOwnershipFingerprintSha256() {
        return workspaceOwnershipFingerprintSha256;
    }
    public void setWorkspaceOwnershipFingerprintSha256(String value) {
        workspaceOwnershipFingerprintSha256 = value;
    }
    public Instant getWorkspaceUpdatedAt() { return workspaceUpdatedAt; }
    public void setWorkspaceUpdatedAt(Instant value) { workspaceUpdatedAt = value; }
    public DevelopmentChangeProjectionState getValidationState() { return validationState; }
    public void setValidationState(DevelopmentChangeProjectionState value) { validationState = value; }
    public DevelopmentChangeProjectionState getReviewState() { return reviewState; }
    public void setReviewState(DevelopmentChangeProjectionState value) { reviewState = value; }
    public DevelopmentChangeProjectionState getIntegrationState() { return integrationState; }
    public void setIntegrationState(DevelopmentChangeProjectionState value) { integrationState = value; }
    public DevelopmentChangeProjectionState getReleaseState() { return releaseState; }
    public void setReleaseState(DevelopmentChangeProjectionState value) { releaseState = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public long getVersion() { return version; }
}
