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
@Table(name = "session_deliverable")
public class SessionDeliverableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private WorkSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SessionDeliverableType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SessionDeliverableStatus status;

    @Column(nullable = false)
    private int version;

    @Column(length = 200)
    private String title;

    @Column(name = "content_markdown")
    private String contentMarkdown;

    @Column(name = "content_json")
    private String contentJson;

    @Column(name = "input_snapshot_json")
    private String inputSnapshotJson;

    @Column(name = "generation_notes")
    private String generationNotes;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(length = 120)
    private String model;

    @Column(name = "prompt_version", length = 80)
    private String promptVersion;

    @Column(nullable = false)
    private boolean approved;

    @Column(name = "approved_at")
    private Instant approvedAt;

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

    public WorkSessionEntity getSession() {
        return session;
    }

    public void setSession(WorkSessionEntity session) {
        this.session = session;
    }

    public SessionDeliverableType getType() {
        return type;
    }

    public void setType(SessionDeliverableType type) {
        this.type = type;
    }

    public SessionDeliverableStatus getStatus() {
        return status;
    }

    public void setStatus(SessionDeliverableStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public void setContentMarkdown(String contentMarkdown) {
        this.contentMarkdown = contentMarkdown;
    }

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public String getInputSnapshotJson() {
        return inputSnapshotJson;
    }

    public void setInputSnapshotJson(String inputSnapshotJson) {
        this.inputSnapshotJson = inputSnapshotJson;
    }

    public String getGenerationNotes() {
        return generationNotes;
    }

    public void setGenerationNotes(String generationNotes) {
        this.generationNotes = generationNotes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
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
