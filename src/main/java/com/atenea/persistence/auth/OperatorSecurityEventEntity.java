package com.atenea.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operator_security_event")
public class OperatorSecurityEventEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id") private OperatorEntity operator;
    @Column(name = "event_type", nullable = false, length = 40, updatable = false) private String eventType;
    @Column(nullable = false, length = 16, updatable = false) private String outcome;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant value) { occurredAt = value; }
}
