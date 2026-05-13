package com.atenea.persistence.rescue;

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
@Table(name = "rescue_session_turn")
public class RescueSessionTurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rescue_session_id", nullable = false)
    private RescueSessionEntity rescueSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RescueSessionTurnActor actor;

    @Column(name = "message_text", nullable = false, columnDefinition = "TEXT")
    private String messageText;

    @Column(name = "external_turn_id", length = 100)
    private String externalTurnId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RescueSessionEntity getRescueSession() {
        return rescueSession;
    }

    public void setRescueSession(RescueSessionEntity rescueSession) {
        this.rescueSession = rescueSession;
    }

    public RescueSessionTurnActor getActor() {
        return actor;
    }

    public void setActor(RescueSessionTurnActor actor) {
        this.actor = actor;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public String getExternalTurnId() {
        return externalTurnId;
    }

    public void setExternalTurnId(String externalTurnId) {
        this.externalTurnId = externalTurnId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
