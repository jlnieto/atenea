package com.atenea.persistence.worksession;

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

    @Column(name = "target_repo_path", nullable = false, length = 500)
    private String targetRepoPath;

    @Column(name = "external_turn_id", length = 100)
    private String externalTurnId;

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
