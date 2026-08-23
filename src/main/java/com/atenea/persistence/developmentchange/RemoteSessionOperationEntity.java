package com.atenea.persistence.developmentchange;

import com.atenea.api.developmentchange.RemoteSessionNextAction;
import com.atenea.api.developmentchange.RemoteSessionRejectionClass;
import com.atenea.api.developmentchange.RemoteSessionResolution;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionStatus;
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
@Table(name = "remote_session_operation")
public class RemoteSessionOperationEntity {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_session_id")
    private WorkSessionEntity workSession;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, length = 48, updatable = false)
    private RemoteSessionOperationKind operationKind;

    @Column(name = "expected_change_revision", nullable = false, updatable = false)
    private long expectedChangeRevision;

    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String requestFingerprintSha256;

    @Column(name = "target_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String targetFingerprintSha256;

    @Column(name = "source_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String sourceFingerprintSha256;

    @Column(name = "ownership_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String ownershipFingerprintSha256;

    @Column(name = "beta_policy_revision", nullable = false, updatable = false)
    private long betaPolicyRevision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RemoteSessionOperationState state;

    @Column(nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RemoteSessionResolution resolution;

    @Column(name = "result_change_revision")
    private Long resultChangeRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_session_state", length = 32)
    private WorkSessionStatus resultSessionState;

    @Column(name = "result_remote_session_id")
    private UUID resultRemoteSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_class", length = 16)
    private RemoteSessionRejectionClass rejectionClass;

    @Column(name = "failure_code", length = 96)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_action", length = 48)
    private RemoteSessionNextAction nextAction;

    @Column(name = "receipt_sha256", length = 64)
    private String receiptSha256;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

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
    public WorkSessionEntity getWorkSession() { return workSession; }
    public void setWorkSession(WorkSessionEntity value) { workSession = value; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID value) { idempotencyKey = value; }
    public RemoteSessionOperationKind getOperationKind() { return operationKind; }
    public void setOperationKind(RemoteSessionOperationKind value) { operationKind = value; }
    public long getExpectedChangeRevision() { return expectedChangeRevision; }
    public void setExpectedChangeRevision(long value) { expectedChangeRevision = value; }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String value) { requestFingerprintSha256 = value; }
    public String getTargetFingerprintSha256() { return targetFingerprintSha256; }
    public void setTargetFingerprintSha256(String value) { targetFingerprintSha256 = value; }
    public String getSourceFingerprintSha256() { return sourceFingerprintSha256; }
    public void setSourceFingerprintSha256(String value) { sourceFingerprintSha256 = value; }
    public String getOwnershipFingerprintSha256() { return ownershipFingerprintSha256; }
    public void setOwnershipFingerprintSha256(String value) { ownershipFingerprintSha256 = value; }
    public long getBetaPolicyRevision() { return betaPolicyRevision; }
    public void setBetaPolicyRevision(long value) { betaPolicyRevision = value; }
    public RemoteSessionOperationState getState() { return state; }
    public void setState(RemoteSessionOperationState value) { state = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public RemoteSessionResolution getResolution() { return resolution; }
    public void setResolution(RemoteSessionResolution value) { resolution = value; }
    public Long getResultChangeRevision() { return resultChangeRevision; }
    public void setResultChangeRevision(Long value) { resultChangeRevision = value; }
    public WorkSessionStatus getResultSessionState() { return resultSessionState; }
    public void setResultSessionState(WorkSessionStatus value) { resultSessionState = value; }
    public UUID getResultRemoteSessionId() { return resultRemoteSessionId; }
    public void setResultRemoteSessionId(UUID value) { resultRemoteSessionId = value; }
    public RemoteSessionRejectionClass getRejectionClass() { return rejectionClass; }
    public void setRejectionClass(RemoteSessionRejectionClass value) { rejectionClass = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public RemoteSessionNextAction getNextAction() { return nextAction; }
    public void setNextAction(RemoteSessionNextAction value) { nextAction = value; }
    public String getReceiptSha256() { return receiptSha256; }
    public void setReceiptSha256(String value) { receiptSha256 = value; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant value) { requestedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
