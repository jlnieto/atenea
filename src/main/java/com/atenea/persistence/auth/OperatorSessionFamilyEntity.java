package com.atenea.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operator_session_family")
public class OperatorSessionFamilyEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private OperatorEntity operator;

    @Column(name = "client_type", nullable = false, length = 24)
    private String clientType;

    @Column(name = "device_label", nullable = false, length = 120)
    private String deviceLabel;

    @Column(name = "current_generation", nullable = false)
    private long currentGeneration;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "authenticated_at")
    private Instant authenticatedAt;

    @Column(name = "authentication_method", length = 20)
    private String authenticationMethod;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 40)
    private String revocationReason;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public String getClientType() { return clientType; }
    public void setClientType(String value) { clientType = value; }
    public String getDeviceLabel() { return deviceLabel; }
    public void setDeviceLabel(String value) { deviceLabel = value; }
    public long getCurrentGeneration() { return currentGeneration; }
    public void setCurrentGeneration(long value) { currentGeneration = value; }
    public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant value) { lastUsedAt = value; }
    public Instant getAbsoluteExpiresAt() { return absoluteExpiresAt; }
    public void setAbsoluteExpiresAt(Instant value) { absoluteExpiresAt = value; }
    public Instant getAuthenticatedAt() { return authenticatedAt; }
    public void setAuthenticatedAt(Instant value) { authenticatedAt = value; }
    public String getAuthenticationMethod() { return authenticationMethod; }
    public void setAuthenticationMethod(String value) { authenticationMethod = value; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant value) { revokedAt = value; }
    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String value) { revocationReason = value; }
}
