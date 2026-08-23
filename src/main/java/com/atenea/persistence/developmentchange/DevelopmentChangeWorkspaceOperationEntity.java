package com.atenea.persistence.developmentchange;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.v2.control.V2FailureCategory;
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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "development_change_workspace_operation")
public class DevelopmentChangeWorkspaceOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, updatable = false, unique = true)
    private UUID operationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false, updatable = false)
    private OperatorEntity operator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "development_change_id", nullable = false, updatable = false)
    private DevelopmentChangeEntity developmentChange;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, length = 16, updatable = false)
    private DevelopmentChangeWorkspaceOperationKind operationKind;

    @Column(name = "predecessor_operation_id", updatable = false)
    private UUID predecessorOperationId;

    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String requestFingerprintSha256;

    @Column(name = "target_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String targetFingerprintSha256;

    @Column(name = "expected_source_revision", nullable = false, updatable = false)
    private long expectedSourceRevision;

    @Column(name = "expected_source_fingerprint_sha256", nullable = false,
            length = 64, updatable = false)
    private String expectedSourceFingerprintSha256;

    @Column(name = "expected_canonical_commit", nullable = false, length = 64, updatable = false)
    private String expectedCanonicalCommit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DevelopmentChangeWorkspaceOperationState state;

    @Column(nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_workspace_state", length = 24)
    private DevelopmentChangeWorkspaceState resultWorkspaceState;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_source_state", length = 24)
    private DevelopmentChangeSourceState resultSourceState;

    @Column(name = "result_source_revision")
    private Long resultSourceRevision;

    @Column(name = "result_source_fingerprint_sha256", length = 64)
    private String resultSourceFingerprintSha256;

    @Column(name = "observed_canonical_commit", length = 64)
    private String observedCanonicalCommit;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", length = 24)
    private V2FailureCategory failureCategory;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "receipt_sha256", length = 64)
    private String receiptSha256;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID value) { operationId = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public ProjectEntity getProject() { return project; }
    public void setProject(ProjectEntity value) { project = value; }
    public DevelopmentChangeEntity getDevelopmentChange() { return developmentChange; }
    public void setDevelopmentChange(DevelopmentChangeEntity value) { developmentChange = value; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID value) { idempotencyKey = value; }
    public DevelopmentChangeWorkspaceOperationKind getOperationKind() { return operationKind; }
    public void setOperationKind(DevelopmentChangeWorkspaceOperationKind value) { operationKind = value; }
    public UUID getPredecessorOperationId() { return predecessorOperationId; }
    public void setPredecessorOperationId(UUID value) { predecessorOperationId = value; }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String value) { requestFingerprintSha256 = value; }
    public String getTargetFingerprintSha256() { return targetFingerprintSha256; }
    public void setTargetFingerprintSha256(String value) { targetFingerprintSha256 = value; }
    public long getExpectedSourceRevision() { return expectedSourceRevision; }
    public void setExpectedSourceRevision(long value) { expectedSourceRevision = value; }
    public String getExpectedSourceFingerprintSha256() { return expectedSourceFingerprintSha256; }
    public void setExpectedSourceFingerprintSha256(String value) { expectedSourceFingerprintSha256 = value; }
    public String getExpectedCanonicalCommit() { return expectedCanonicalCommit; }
    public void setExpectedCanonicalCommit(String value) { expectedCanonicalCommit = value; }
    public DevelopmentChangeWorkspaceOperationState getState() { return state; }
    public void setState(DevelopmentChangeWorkspaceOperationState value) { state = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public DevelopmentChangeWorkspaceState getResultWorkspaceState() { return resultWorkspaceState; }
    public void setResultWorkspaceState(DevelopmentChangeWorkspaceState value) { resultWorkspaceState = value; }
    public DevelopmentChangeSourceState getResultSourceState() { return resultSourceState; }
    public void setResultSourceState(DevelopmentChangeSourceState value) { resultSourceState = value; }
    public Long getResultSourceRevision() { return resultSourceRevision; }
    public void setResultSourceRevision(Long value) { resultSourceRevision = value; }
    public String getResultSourceFingerprintSha256() { return resultSourceFingerprintSha256; }
    public void setResultSourceFingerprintSha256(String value) { resultSourceFingerprintSha256 = value; }
    public String getObservedCanonicalCommit() { return observedCanonicalCommit; }
    public void setObservedCanonicalCommit(String value) { observedCanonicalCommit = value; }
    public V2FailureCategory getFailureCategory() { return failureCategory; }
    public void setFailureCategory(V2FailureCategory value) { failureCategory = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public String getReceiptSha256() { return receiptSha256; }
    public void setReceiptSha256(String value) { receiptSha256 = value; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant value) { requestedAt = value; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant value) { dispatchedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
