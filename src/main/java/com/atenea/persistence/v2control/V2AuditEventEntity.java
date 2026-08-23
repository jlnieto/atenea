package com.atenea.persistence.v2control;

import com.atenea.v2.control.V2FailureCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "v2_audit_event")
public class V2AuditEventEntity {

    @Id private UUID id;
    @Column(name = "operation_id", nullable = false, updatable = false) private UUID operationId;
    @Column(name = "project_id", nullable = false, updatable = false) private Long projectId;
    @Column(name = "actor_id", nullable = false, updatable = false) private Long actorId;
    @Column(nullable = false, length = 80, updatable = false) private String capability;
    @Column(name = "event_type", nullable = false, length = 80, updatable = false) private String eventType;
    @Column(nullable = false, length = 40, updatable = false) private String state;
    @Column(nullable = false, updatable = false) private long revision;
    @Column(name = "request_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String requestFingerprintSha256;
    @Column(name = "target_fingerprint_sha256", nullable = false, length = 64, updatable = false)
    private String targetFingerprintSha256;
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category", length = 24, updatable = false)
    private V2FailureCategory failureCategory;
    @Column(name = "failure_code", length = 80, updatable = false) private String failureCode;
    @Column(name = "item_count", nullable = false, updatable = false) private int itemCount;
    @Column(name = "duration_millis", nullable = false, updatable = false) private long durationMillis;
    @Column(name = "occurred_at", nullable = false, updatable = false) private Instant occurredAt;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getOperationId() { return operationId; }
    public void setOperationId(UUID value) { operationId = value; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long value) { projectId = value; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long value) { actorId = value; }
    public String getCapability() { return capability; }
    public void setCapability(String value) { capability = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public String getRequestFingerprintSha256() { return requestFingerprintSha256; }
    public void setRequestFingerprintSha256(String value) { requestFingerprintSha256 = value; }
    public String getTargetFingerprintSha256() { return targetFingerprintSha256; }
    public void setTargetFingerprintSha256(String value) { targetFingerprintSha256 = value; }
    public V2FailureCategory getFailureCategory() { return failureCategory; }
    public void setFailureCategory(V2FailureCategory value) { failureCategory = value; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String value) { failureCode = value; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int value) { itemCount = value; }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long value) { durationMillis = value; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant value) { occurredAt = value; }
}
