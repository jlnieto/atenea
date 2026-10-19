package com.atenea.persistence.notification;

import com.atenea.persistence.auth.OperatorEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mobile_notification_inbox", uniqueConstraints =
        @UniqueConstraint(name = "uk_mobile_notification_inbox_event", columnNames = {"operator_id", "event_key"}))
public class MobileNotificationInboxEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false) private OperatorEntity operator;
    @Column(name = "event_key", nullable = false, length = 255) private String eventKey;
    @Column(nullable = false, length = 64) private String category;
    @Column(nullable = false, length = 190) private String title;
    @Column(nullable = false, length = 500) private String body;
    @Column(nullable = false, length = 32) private String destination;
    @Column(name = "target_id") private Long targetId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "read_at") private Instant readAt;
    public UUID getId() { return id; } public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; } public void setOperator(OperatorEntity value) { operator = value; }
    public String getEventKey() { return eventKey; } public void setEventKey(String value) { eventKey = value; }
    public String getCategory() { return category; } public void setCategory(String value) { category = value; }
    public String getTitle() { return title; } public void setTitle(String value) { title = value; }
    public String getBody() { return body; } public void setBody(String value) { body = value; }
    public String getDestination() { return destination; } public void setDestination(String value) { destination = value; }
    public Long getTargetId() { return targetId; } public void setTargetId(Long value) { targetId = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getReadAt() { return readAt; } public void setReadAt(Instant value) { readAt = value; }
}
