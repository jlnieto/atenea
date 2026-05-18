package com.atenea.persistence.verification;

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

@Entity
@Table(name = "project_verification_run")
public class ProjectVerificationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_session_id")
    private WorkSessionEntity workSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectVerificationStatus status;

    @Column(name = "runtime_contract_path", length = 600)
    private String runtimeContractPath;

    @Column(name = "runtime_profile", length = 80)
    private String runtimeProfile;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

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

    @Column(name = "tests_json", columnDefinition = "TEXT")
    private String testsJson;

    @Column(name = "artifacts_json", columnDefinition = "TEXT")
    private String artifactsJson;

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

    public WorkSessionEntity getWorkSession() {
        return workSession;
    }

    public void setWorkSession(WorkSessionEntity workSession) {
        this.workSession = workSession;
    }

    public ProjectVerificationStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectVerificationStatus status) {
        this.status = status;
    }

    public String getRuntimeContractPath() {
        return runtimeContractPath;
    }

    public void setRuntimeContractPath(String runtimeContractPath) {
        this.runtimeContractPath = runtimeContractPath;
    }

    public String getRuntimeProfile() {
        return runtimeProfile;
    }

    public void setRuntimeProfile(String runtimeProfile) {
        this.runtimeProfile = runtimeProfile;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
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

    public String getTestsJson() {
        return testsJson;
    }

    public void setTestsJson(String testsJson) {
        this.testsJson = testsJson;
    }

    public String getArtifactsJson() {
        return artifactsJson;
    }

    public void setArtifactsJson(String artifactsJson) {
        this.artifactsJson = artifactsJson;
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
