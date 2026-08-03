package com.atenea.persistence.notification;

import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "notification_preference", uniqueConstraints = @UniqueConstraint(name = "uk_notification_preference_device_category", columnNames = {"device_id", "category"}))
public class NotificationPreferenceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "device_id", nullable = false) private OperatorPushDeviceEntity device;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private NotificationCategory category;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    public Long getId() { return id; } public void setId(Long value) { id = value; }
    public OperatorPushDeviceEntity getDevice() { return device; } public void setDevice(OperatorPushDeviceEntity value) { device = value; }
    public NotificationCategory getCategory() { return category; } public void setCategory(NotificationCategory value) { category = value; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant value) { updatedAt = value; }
}
