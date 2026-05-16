package com.atenea.persistence.voice;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
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
@Table(name = "voice_note_send_intent")
public class VoiceNoteSendIntentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private OperatorEntity operator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VoiceNoteSendIntentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_type", nullable = false, length = 40)
    private VoiceNoteSendDestinationType destinationType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;

    @Column(name = "project_name", length = 160)
    private String projectName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_session_id")
    private WorkSessionEntity workSession;

    @Column(name = "work_session_title", length = 220)
    private String workSessionTitle;

    @Column(name = "note_ids_json", nullable = false)
    private String noteIdsJson;

    @Column(length = 500)
    private String instruction;

    @Column(name = "confirmation_token", nullable = false, length = 80)
    private String confirmationToken;

    @Column(name = "confirmation_prompt", nullable = false)
    private String confirmationPrompt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_run_id")
    private AgentRunEntity agentRun;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OperatorEntity getOperator() {
        return operator;
    }

    public void setOperator(OperatorEntity operator) {
        this.operator = operator;
    }

    public VoiceNoteSendIntentStatus getStatus() {
        return status;
    }

    public void setStatus(VoiceNoteSendIntentStatus status) {
        this.status = status;
    }

    public VoiceNoteSendDestinationType getDestinationType() {
        return destinationType;
    }

    public void setDestinationType(VoiceNoteSendDestinationType destinationType) {
        this.destinationType = destinationType;
    }

    public ProjectEntity getProject() {
        return project;
    }

    public void setProject(ProjectEntity project) {
        this.project = project;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public WorkSessionEntity getWorkSession() {
        return workSession;
    }

    public void setWorkSession(WorkSessionEntity workSession) {
        this.workSession = workSession;
    }

    public String getWorkSessionTitle() {
        return workSessionTitle;
    }

    public void setWorkSessionTitle(String workSessionTitle) {
        this.workSessionTitle = workSessionTitle;
    }

    public String getNoteIdsJson() {
        return noteIdsJson;
    }

    public void setNoteIdsJson(String noteIdsJson) {
        this.noteIdsJson = noteIdsJson;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getConfirmationToken() {
        return confirmationToken;
    }

    public void setConfirmationToken(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    public String getConfirmationPrompt() {
        return confirmationPrompt;
    }

    public void setConfirmationPrompt(String confirmationPrompt) {
        this.confirmationPrompt = confirmationPrompt;
    }

    public AgentRunEntity getAgentRun() {
        return agentRun;
    }

    public void setAgentRun(AgentRunEntity agentRun) {
        this.agentRun = agentRun;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
