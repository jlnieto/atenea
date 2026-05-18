package com.atenea.persistence.database;

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
import java.time.Instant;

@Entity
@Table(name = "project_database_refresh_run")
public class ProjectDatabaseRefreshRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectDatabaseRefreshStatus status;

    @Column(name = "runtime_contract_path", length = 600)
    private String runtimeContractPath;

    @Column(name = "database_engine", length = 40)
    private String databaseEngine;

    @Column(name = "local_database", length = 160)
    private String localDatabase;

    @Column(name = "source_host", length = 160)
    private String sourceHost;

    @Column(name = "source_database", length = 160)
    private String sourceDatabase;

    @Column(name = "decision_brief", columnDefinition = "TEXT")
    private String decisionBrief;

    @Column(name = "technical_summary", columnDefinition = "TEXT")
    private String technicalSummary;

    @Column(name = "blocker_type", length = 80)
    private String blockerType;

    @Column(name = "blocker_summary", columnDefinition = "TEXT")
    private String blockerSummary;

    @Column(name = "recommended_action", columnDefinition = "TEXT")
    private String recommendedAction;

    @Column(name = "command_exit_code")
    private Integer commandExitCode;

    @Column(name = "command_output_summary", columnDefinition = "TEXT")
    private String commandOutputSummary;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public ProjectDatabaseRefreshStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectDatabaseRefreshStatus status) {
        this.status = status;
    }

    public String getRuntimeContractPath() {
        return runtimeContractPath;
    }

    public void setRuntimeContractPath(String runtimeContractPath) {
        this.runtimeContractPath = runtimeContractPath;
    }

    public String getDatabaseEngine() {
        return databaseEngine;
    }

    public void setDatabaseEngine(String databaseEngine) {
        this.databaseEngine = databaseEngine;
    }

    public String getLocalDatabase() {
        return localDatabase;
    }

    public void setLocalDatabase(String localDatabase) {
        this.localDatabase = localDatabase;
    }

    public String getSourceHost() {
        return sourceHost;
    }

    public void setSourceHost(String sourceHost) {
        this.sourceHost = sourceHost;
    }

    public String getSourceDatabase() {
        return sourceDatabase;
    }

    public void setSourceDatabase(String sourceDatabase) {
        this.sourceDatabase = sourceDatabase;
    }

    public String getDecisionBrief() {
        return decisionBrief;
    }

    public void setDecisionBrief(String decisionBrief) {
        this.decisionBrief = decisionBrief;
    }

    public String getTechnicalSummary() {
        return technicalSummary;
    }

    public void setTechnicalSummary(String technicalSummary) {
        this.technicalSummary = technicalSummary;
    }

    public String getBlockerType() {
        return blockerType;
    }

    public void setBlockerType(String blockerType) {
        this.blockerType = blockerType;
    }

    public String getBlockerSummary() {
        return blockerSummary;
    }

    public void setBlockerSummary(String blockerSummary) {
        this.blockerSummary = blockerSummary;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public Integer getCommandExitCode() {
        return commandExitCode;
    }

    public void setCommandExitCode(Integer commandExitCode) {
        this.commandExitCode = commandExitCode;
    }

    public String getCommandOutputSummary() {
        return commandOutputSummary;
    }

    public void setCommandOutputSummary(String commandOutputSummary) {
        this.commandOutputSummary = commandOutputSummary;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(Long durationMillis) {
        this.durationMillis = durationMillis;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
