package com.atenea.persistence.task;

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
@Table(name = "task")
public class TaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "base_branch", nullable = false, length = 120)
    private String baseBranch;

    @Column(name = "branch_name", nullable = false, length = 180, unique = true)
    private String branchName;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_status", nullable = false, length = 32)
    private TaskBranchStatus branchStatus;

    @Column(name = "pull_request_url", length = 500)
    private String pullRequestUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "pull_request_status", nullable = false, length = 32)
    private TaskPullRequestStatus pullRequestStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", nullable = false, length = 32)
    private TaskReviewOutcome reviewOutcome;

    @Column(name = "review_notes", length = 1000)
    private String reviewNotes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskPriority priority;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public TaskBranchStatus getBranchStatus() {
        return branchStatus;
    }

    public void setBranchStatus(TaskBranchStatus branchStatus) {
        this.branchStatus = branchStatus;
    }

    public String getPullRequestUrl() {
        return pullRequestUrl;
    }

    public void setPullRequestUrl(String pullRequestUrl) {
        this.pullRequestUrl = pullRequestUrl;
    }

    public TaskPullRequestStatus getPullRequestStatus() {
        return pullRequestStatus;
    }

    public void setPullRequestStatus(TaskPullRequestStatus pullRequestStatus) {
        this.pullRequestStatus = pullRequestStatus;
    }

    public TaskReviewOutcome getReviewOutcome() {
        return reviewOutcome;
    }

    public void setReviewOutcome(TaskReviewOutcome reviewOutcome) {
        this.reviewOutcome = reviewOutcome;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
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
