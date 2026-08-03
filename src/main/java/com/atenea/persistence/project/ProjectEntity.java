package com.atenea.persistence.project;

import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.CodexReasoningEffortConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "project")
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "repo_path", nullable = false, length = 500)
    private String repoPath;

    @Column(name = "default_base_branch", length = 120)
    private String defaultBaseBranch;

    @Column(name = "default_codex_model_id", length = 80)
    private String defaultCodexModelId;

    @Convert(converter = CodexReasoningEffortConverter.class)
    @Column(name = "default_codex_reasoning_effort", length = 16)
    private CodexReasoningEffort defaultCodexReasoningEffort;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }

    public String getDefaultBaseBranch() {
        return defaultBaseBranch;
    }

    public void setDefaultBaseBranch(String defaultBaseBranch) {
        this.defaultBaseBranch = defaultBaseBranch;
    }

    public String getDefaultCodexModelId() {
        return defaultCodexModelId;
    }

    public void setDefaultCodexModelId(String defaultCodexModelId) {
        this.defaultCodexModelId = defaultCodexModelId;
    }

    public CodexReasoningEffort getDefaultCodexReasoningEffort() {
        return defaultCodexReasoningEffort;
    }

    public void setDefaultCodexReasoningEffort(CodexReasoningEffort defaultCodexReasoningEffort) {
        this.defaultCodexReasoningEffort = defaultCodexReasoningEffort;
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
