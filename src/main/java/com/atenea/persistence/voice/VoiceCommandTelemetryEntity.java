package com.atenea.persistence.voice;

import com.atenea.persistence.auth.OperatorEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "voice_command_telemetry")
public class VoiceCommandTelemetryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private OperatorEntity operator;

    @Column(name = "client_event_id", length = 80)
    private String clientEventId;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(nullable = false, length = 40)
    private String outcome;

    @Column(nullable = false, length = 120)
    private String reason;

    @Column(nullable = false)
    private String transcript;

    @Column(name = "normalized_transcript")
    private String normalizedTranscript;

    @Column(name = "wake_word_detected", nullable = false)
    private boolean wakeWordDetected;

    @Column(name = "starts_with_wake_word", nullable = false)
    private boolean startsWithWakeWord;

    @Column(name = "intent_type", length = 120)
    private String intentType;

    @Column(length = 32)
    private String domain;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "project_name", length = 160)
    private String projectName;

    @Column(name = "work_session_id")
    private Long workSessionId;

    @Column(name = "work_session_title", length = 220)
    private String workSessionTitle;

    @Column(name = "active_command_id")
    private Long activeCommandId;

    @Column(name = "active_note_count")
    private Integer activeNoteCount;

    @Column(name = "pending_send_intent_id")
    private Long pendingSendIntentId;

    @Column(name = "realtime_connected")
    private Boolean realtimeConnected;

    @Column(name = "voice_state", length = 80)
    private String voiceState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public OperatorEntity getOperator() {
        return operator;
    }

    public void setOperator(OperatorEntity operator) {
        this.operator = operator;
    }

    public String getClientEventId() {
        return clientEventId;
    }

    public void setClientEventId(String clientEventId) {
        this.clientEventId = clientEventId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public String getNormalizedTranscript() {
        return normalizedTranscript;
    }

    public void setNormalizedTranscript(String normalizedTranscript) {
        this.normalizedTranscript = normalizedTranscript;
    }

    public boolean isWakeWordDetected() {
        return wakeWordDetected;
    }

    public void setWakeWordDetected(boolean wakeWordDetected) {
        this.wakeWordDetected = wakeWordDetected;
    }

    public boolean isStartsWithWakeWord() {
        return startsWithWakeWord;
    }

    public void setStartsWithWakeWord(boolean startsWithWakeWord) {
        this.startsWithWakeWord = startsWithWakeWord;
    }

    public String getIntentType() {
        return intentType;
    }

    public void setIntentType(String intentType) {
        this.intentType = intentType;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public Long getWorkSessionId() {
        return workSessionId;
    }

    public void setWorkSessionId(Long workSessionId) {
        this.workSessionId = workSessionId;
    }

    public String getWorkSessionTitle() {
        return workSessionTitle;
    }

    public void setWorkSessionTitle(String workSessionTitle) {
        this.workSessionTitle = workSessionTitle;
    }

    public Long getActiveCommandId() {
        return activeCommandId;
    }

    public void setActiveCommandId(Long activeCommandId) {
        this.activeCommandId = activeCommandId;
    }

    public Integer getActiveNoteCount() {
        return activeNoteCount;
    }

    public void setActiveNoteCount(Integer activeNoteCount) {
        this.activeNoteCount = activeNoteCount;
    }

    public Long getPendingSendIntentId() {
        return pendingSendIntentId;
    }

    public void setPendingSendIntentId(Long pendingSendIntentId) {
        this.pendingSendIntentId = pendingSendIntentId;
    }

    public Boolean getRealtimeConnected() {
        return realtimeConnected;
    }

    public void setRealtimeConnected(Boolean realtimeConnected) {
        this.realtimeConnected = realtimeConnected;
    }

    public String getVoiceState() {
        return voiceState;
    }

    public void setVoiceState(String voiceState) {
        this.voiceState = voiceState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
