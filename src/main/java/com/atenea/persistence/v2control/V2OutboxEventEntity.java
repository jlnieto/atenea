package com.atenea.persistence.v2control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "v2_outbox_event")
public class V2OutboxEventEntity {

    @Id private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audit_event_id", nullable = false, unique = true, updatable = false)
    private V2AuditEventEntity auditEvent;
    @Column(name = "operation_id", nullable = false, updatable = false) private UUID operationId;
    @Column(nullable = false, length = 80, updatable = false) private String capability;
    @Column(name = "event_type", nullable = false, length = 80, updatable = false) private String eventType;
    @Column(nullable = false, updatable = false) private long revision;
    @Column(name = "deduplication_sha256", nullable = false, unique = true, length = 64, updatable = false)
    private String deduplicationSha256;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private V2OutboxState state;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "failure_code", length = 80) private String failureCode;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public V2AuditEventEntity getAuditEvent() { return auditEvent; }
    public void setAuditEvent(V2AuditEventEntity value) { auditEvent = value; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID value) { operationId = value; }
    public String getCapability() { return capability; }
    public void setCapability(String value) { capability = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public String getDeduplicationSha256() { return deduplicationSha256; }
    public void setDeduplicationSha256(String value) { deduplicationSha256 = value; }
    public V2OutboxState getState() { return state; }
    public void setState(V2OutboxState value) { state = value; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int value) { attemptCount = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant value) { publishedAt = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
