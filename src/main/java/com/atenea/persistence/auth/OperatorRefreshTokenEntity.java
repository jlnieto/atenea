package com.atenea.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "operator_refresh_token")
public class OperatorRefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private OperatorEntity operator;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_family_id")
    private OperatorSessionFamilyEntity sessionFamily;

    @Column(name = "generation")
    private Long generation;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "replaced_by_token_id")
    private Long replacedByTokenId;

    @Column(name = "revocation_reason", length = 40)
    private String revocationReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OperatorEntity getOperator() {
        return operator;
    }

    public void setOperator(OperatorEntity operator) {
        this.operator = operator;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public OperatorSessionFamilyEntity getSessionFamily() { return sessionFamily; }
    public void setSessionFamily(OperatorSessionFamilyEntity value) { sessionFamily = value; }

    public Long getGeneration() { return generation; }
    public void setGeneration(Long value) { generation = value; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { consumedAt = value; }

    public Long getReplacedByTokenId() { return replacedByTokenId; }
    public void setReplacedByTokenId(Long value) { replacedByTokenId = value; }

    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String value) { revocationReason = value; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
