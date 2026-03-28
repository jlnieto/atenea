package com.atenea.persistence.worksession;

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
