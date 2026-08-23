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
@Table(name = "operator_security_notification")
public class OperatorSecurityNotificationEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "security_event_id", nullable = false, unique = true) private OperatorSecurityEventEntity securityEvent;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false) private OperatorEntity operator;
    @Column(name = "template_code", nullable = false, length = 40) private String templateCode;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorSecurityEventEntity getSecurityEvent() { return securityEvent; }
    public void setSecurityEvent(OperatorSecurityEventEntity value) { securityEvent = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String value) { templateCode = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
