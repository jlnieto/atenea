package com.atenea.persistence.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "core_command")
public class CoreCommandEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_input", nullable = false)
    private String rawInput;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CoreChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CoreCommandStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CoreDomain domain;

    @Column(length = 80)
    private String intent;

    @Column(length = 80)
    private String capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 32)
    private CoreRiskLevel riskLevel;

    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation;

    @Column(name = "confirmation_token", length = 120)
    private String confirmationToken;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "request_context_json")
    private String requestContextJson;

    @Column(name = "parameters_json")
    private String parametersJson;

    @Column(name = "interpreted_intent_json")
    private String interpretedIntentJson;

    @Column(name = "clarification_json")
    private String clarificationJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", length = 64)
    private CoreResultType resultType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 64)
    private CoreTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "result_summary")
    private String resultSummary;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "operator_message")
    private String operatorMessage;

    @Column(name = "speakable_message")
    private String speakableMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "interpreter_source", length = 32)
    private CoreInterpreterSource interpreterSource;

    @Column(name = "interpreter_detail")
    private String interpreterDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRawInput() {
        return rawInput;
    }

    public void setRawInput(String rawInput) {
        this.rawInput = rawInput;
    }

    public CoreChannel getChannel() {
        return channel;
    }

    public void setChannel(CoreChannel channel) {
        this.channel = channel;
    }

    public CoreCommandStatus getStatus() {
        return status;
    }

    public void setStatus(CoreCommandStatus status) {
        this.status = status;
    }

    public CoreDomain getDomain() {
        return domain;
    }

    public void setDomain(CoreDomain domain) {
        this.domain = domain;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public CoreRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(CoreRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getConfirmationToken() {
        return confirmationToken;
    }

    public void setConfirmationToken(String confirmationToken) {
        this.confirmationToken = confirmationToken;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getRequestContextJson() {
        return requestContextJson;
    }

    public void setRequestContextJson(String requestContextJson) {
        this.requestContextJson = requestContextJson;
    }

    public String getParametersJson() {
        return parametersJson;
    }

    public void setParametersJson(String parametersJson) {
        this.parametersJson = parametersJson;
    }

    public String getInterpretedIntentJson() {
        return interpretedIntentJson;
    }

    public void setInterpretedIntentJson(String interpretedIntentJson) {
        this.interpretedIntentJson = interpretedIntentJson;
    }

    public String getClarificationJson() {
        return clarificationJson;
    }

    public void setClarificationJson(String clarificationJson) {
        this.clarificationJson = clarificationJson;
    }

    public CoreResultType getResultType() {
        return resultType;
    }

    public void setResultType(CoreResultType resultType) {
        this.resultType = resultType;
    }

    public CoreTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(CoreTargetType targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOperatorMessage() {
        return operatorMessage;
    }

    public void setOperatorMessage(String operatorMessage) {
        this.operatorMessage = operatorMessage;
    }

    public String getSpeakableMessage() {
        return speakableMessage;
    }

    public void setSpeakableMessage(String speakableMessage) {
        this.speakableMessage = speakableMessage;
    }

    public CoreInterpreterSource getInterpreterSource() {
        return interpreterSource;
    }

    public void setInterpreterSource(CoreInterpreterSource interpreterSource) {
        this.interpreterSource = interpreterSource;
    }

    public String getInterpreterDetail() {
        return interpreterDetail;
    }

    public void setInterpreterDetail(String interpreterDetail) {
        this.interpreterDetail = interpreterDetail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
