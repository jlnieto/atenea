package com.atenea.persistence.notification;

import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_delivery", uniqueConstraints = @UniqueConstraint(name = "uk_notification_delivery_owner", columnNames = {"event_id", "device_id", "channel"}))
public class NotificationDeliveryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id", nullable = false) private NotificationEventEntity event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id", nullable = false) private OperatorPushDeviceEntity device;
    @Column(nullable = false, length = 16) private String channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private NotificationDeliveryState state;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "diagnostic_code", length = 40) private String diagnosticCode;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    public Long getId() { return id; } public void setId(Long value) { id = value; }
    public NotificationEventEntity getEvent() { return event; } public void setEvent(NotificationEventEntity value) { event = value; }
    public OperatorPushDeviceEntity getDevice() { return device; } public void setDevice(OperatorPushDeviceEntity value) { device = value; }
    public String getChannel() { return channel; } public void setChannel(String value) { channel = value; }
    public NotificationDeliveryState getState() { return state; } public void setState(NotificationDeliveryState value) { state = value; }
    public int getAttemptCount() { return attemptCount; } public void setAttemptCount(int value) { attemptCount = value; }
    public Instant getNextAttemptAt() { return nextAttemptAt; } public void setNextAttemptAt(Instant value) { nextAttemptAt = value; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getDeliveredAt() { return deliveredAt; } public void setDeliveredAt(Instant value) { deliveredAt = value; }
    public String getDiagnosticCode() { return diagnosticCode; } public void setDiagnosticCode(String value) { diagnosticCode = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant value) { updatedAt = value; }
}
