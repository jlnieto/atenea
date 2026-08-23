package com.atenea.persistence.developmentchange;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.WorkSessionEntity;
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
@Table(name = "development_change_operation")
public class DevelopmentChangeOperationEntity {

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

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_kind", nullable = false, length = 24, updatable = false)
    private DevelopmentChangeOperationKind operationKind;

    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String requestFingerprintSha256;

    @Column(name = "target_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String targetFingerprintSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DevelopmentChangeOperationState state;

    @Column(nullable = false)
    private long revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "development_change_id")
    private DevelopmentChangeEntity developmentChange;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_session_id")
    private WorkSessionEntity workSession;

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
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID value) { idempotencyKey = value; }
    public DevelopmentChangeOperationKind getOperationKind() { return operationKind; }
    public void setOperationKind(DevelopmentChangeOperationKind value) { operationKind = value; }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String value) { requestFingerprintSha256 = value; }
    public String getTargetFingerprintSha256() { return targetFingerprintSha256; }
    public void setTargetFingerprintSha256(String value) { targetFingerprintSha256 = value; }
    public DevelopmentChangeOperationState getState() { return state; }
    public void setState(DevelopmentChangeOperationState value) { state = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public DevelopmentChangeEntity getDevelopmentChange() { return developmentChange; }
    public void setDevelopmentChange(DevelopmentChangeEntity value) { developmentChange = value; }
    public WorkSessionEntity getWorkSession() { return workSession; }
    public void setWorkSession(WorkSessionEntity value) { workSession = value; }
    public String getReceiptSha256() { return receiptSha256; }
    public void setReceiptSha256(String value) { receiptSha256 = value; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant value) { requestedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
