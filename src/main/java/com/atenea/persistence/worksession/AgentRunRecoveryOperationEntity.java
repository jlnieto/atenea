package com.atenea.persistence.worksession;

import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "agent_run_recovery_operation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_run_recovery_idempotency",
                columnNames = {"operator_id", "idempotency_key"}))
public class AgentRunRecoveryOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, unique = true, updatable = false)
    private UUID operationId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String requestFingerprintSha256;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false, updatable = false)
    private OperatorEntity operator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private WorkSessionEntity session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_run_id", nullable = false, updatable = false)
    private AgentRunEntity agentRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_role", nullable = false, length = 32, updatable = false)
    private CodexOperationsRole requestedRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private AgentRunRecoveryAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentRunRecoveryState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome_code", length = 40)
    private AgentRunRecoveryOutcome outcomeCode;

    @Column(name = "outcome_summary", length = 240)
    private String outcomeSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_next_action", length = 40)
    private AgentRunRecoveryNextAction requiredNextAction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_agent_run_id")
    private AgentRunEntity resultAgentRun;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID value) { operationId = value; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(UUID value) { idempotencyKey = value; }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String value) { requestFingerprintSha256 = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public WorkSessionEntity getSession() { return session; }
    public void setSession(WorkSessionEntity value) { session = value; }
    public AgentRunEntity getAgentRun() { return agentRun; }
    public void setAgentRun(AgentRunEntity value) { agentRun = value; }
    public CodexOperationsRole getRequestedRole() { return requestedRole; }
    public void setRequestedRole(CodexOperationsRole value) { requestedRole = value; }
    public AgentRunRecoveryAction getAction() { return action; }
    public void setAction(AgentRunRecoveryAction value) { action = value; }
    public AgentRunRecoveryState getState() { return state; }
    public void setState(AgentRunRecoveryState value) { state = value; }
    public AgentRunRecoveryOutcome getOutcomeCode() { return outcomeCode; }
    public void setOutcomeCode(AgentRunRecoveryOutcome value) { outcomeCode = value; }
    public String getOutcomeSummary() { return outcomeSummary; }
    public void setOutcomeSummary(String value) { outcomeSummary = value; }
    public AgentRunRecoveryNextAction getRequiredNextAction() { return requiredNextAction; }
    public void setRequiredNextAction(AgentRunRecoveryNextAction value) { requiredNextAction = value; }
    public AgentRunEntity getResultAgentRun() { return resultAgentRun; }
    public void setResultAgentRun(AgentRunEntity value) { resultAgentRun = value; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant value) { requestedAt = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { completedAt = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
