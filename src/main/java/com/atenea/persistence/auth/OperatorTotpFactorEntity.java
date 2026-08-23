package com.atenea.persistence.auth;

import com.atenea.auth.recovery.TotpFactorState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "operator_totp_factor")
public class OperatorTotpFactorEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false) private OperatorEntity operator;
    @Column(name = "enrollment_id", nullable = false, unique = true) private UUID enrollmentId;
    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "bytea") private byte[] encryptedSecret;
    @Column(name = "secret_key_version", nullable = false, length = 32) private String secretKeyVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private TotpFactorState state;
    @Column(name = "last_accepted_counter") private Long lastAcceptedCounter;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "activated_at") private Instant activatedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "revocation_reason", length = 40) private String revocationReason;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public UUID getEnrollmentId() { return enrollmentId; }
    public void setEnrollmentId(UUID value) { enrollmentId = value; }
    public byte[] getEncryptedSecret() { return encryptedSecret == null ? null : encryptedSecret.clone(); }
    public void setEncryptedSecret(byte[] value) { encryptedSecret = value == null ? null : value.clone(); }
    public String getSecretKeyVersion() { return secretKeyVersion; }
    public void setSecretKeyVersion(String value) { secretKeyVersion = value; }
    public TotpFactorState getState() { return state; }
    public void setState(TotpFactorState value) { state = value; }
    public Long getLastAcceptedCounter() { return lastAcceptedCounter; }
    public void setLastAcceptedCounter(Long value) { lastAcceptedCounter = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant value) { activatedAt = value; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant value) { revokedAt = value; }
    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String value) { revocationReason = value; }
    public long getRowVersion() { return rowVersion; }
}
