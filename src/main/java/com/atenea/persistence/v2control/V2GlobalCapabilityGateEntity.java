package com.atenea.persistence.v2control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "v2_global_capability_gate")
public class V2GlobalCapabilityGateEntity {

    @Id
    @Column(nullable = false, length = 80, updatable = false)
    private String capability;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private long revision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getCapability() { return capability; }
    public void setCapability(String value) { capability = value; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
