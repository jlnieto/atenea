package com.atenea.persistence.taskexecution;

import com.atenea.persistence.task.TaskEntity;
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
@Table(name = "task_execution")
public class TaskExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private TaskEntity task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "runner_type", nullable = false, length = 16)
    private TaskExecutionRunnerType runnerType;

    @Column(name = "target_repo_path", nullable = false, length = 500)
    private String targetRepoPath;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "external_thread_id", length = 100)
    private String externalThreadId;

    @Column(name = "external_turn_id", length = 100)
    private String externalTurnId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TaskEntity getTask() {
        return task;
    }

    public void setTask(TaskEntity task) {
        this.task = task;
    }

    public TaskExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(TaskExecutionStatus status) {
        this.status = status;
    }

    public TaskExecutionRunnerType getRunnerType() {
        return runnerType;
    }

    public void setRunnerType(TaskExecutionRunnerType runnerType) {
        this.runnerType = runnerType;
    }

    public String getTargetRepoPath() {
        return targetRepoPath;
    }

    public void setTargetRepoPath(String targetRepoPath) {
        this.targetRepoPath = targetRepoPath;
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

    public String getExternalThreadId() {
        return externalThreadId;
    }

    public void setExternalThreadId(String externalThreadId) {
        this.externalThreadId = externalThreadId;
    }

    public String getExternalTurnId() {
        return externalTurnId;
    }

    public void setExternalTurnId(String externalTurnId) {
        this.externalTurnId = externalTurnId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
