package com.atenea.persistence.worksession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "worker_node")
public class WorkerNodeEntity {

    @Id
    @Column(length = 80)
    private String id;

    @Column(name = "protocol_version", nullable = false, length = 80)
    private String protocolVersion;

    @Column(nullable = false, length = 500)
    private String endpoint;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private boolean healthy;

    @Column(name = "normal_capacity", nullable = false)
    private int normalCapacity;

    @Column(name = "heavy_capacity", nullable = false)
    private int heavyCapacity;

    @Column(name = "normal_in_use", nullable = false)
    private int normalInUse;

    @Column(name = "heavy_in_use", nullable = false)
    private int heavyInUse;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String capabilities;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "unavailable_reason", length = 500)
    private String unavailableReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }
    public int getNormalCapacity() { return normalCapacity; }
    public void setNormalCapacity(int normalCapacity) { this.normalCapacity = normalCapacity; }
    public int getHeavyCapacity() { return heavyCapacity; }
    public void setHeavyCapacity(int heavyCapacity) { this.heavyCapacity = heavyCapacity; }
    public int getNormalInUse() { return normalInUse; }
    public void setNormalInUse(int normalInUse) { this.normalInUse = normalInUse; }
    public int getHeavyInUse() { return heavyInUse; }
    public void setHeavyInUse(int heavyInUse) { this.heavyInUse = heavyInUse; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public String getUnavailableReason() { return unavailableReason; }
    public void setUnavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
