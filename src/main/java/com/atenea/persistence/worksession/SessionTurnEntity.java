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
import java.util.UUID;

@Entity
@Table(name = "session_turn")
public class SessionTurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private WorkSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionTurnActor actor;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @Column(nullable = false)
    private boolean internal;

    @Column(name = "client_request_id", updatable = false)
    private UUID clientRequestId;

    @Column(name = "request_fingerprint_sha256", length = 64, updatable = false)
    private String requestFingerprintSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public SessionTurnActor getActor() {
        return actor;
    }

    public void setActor(SessionTurnActor actor) {
        this.actor = actor;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public boolean isInternal() {
        return internal;
    }

    public void setInternal(boolean internal) {
        this.internal = internal;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(UUID clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getRequestFingerprintSha256() {
        return requestFingerprintSha256;
    }

    public void setRequestFingerprintSha256(String requestFingerprintSha256) {
        this.requestFingerprintSha256 = requestFingerprintSha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
