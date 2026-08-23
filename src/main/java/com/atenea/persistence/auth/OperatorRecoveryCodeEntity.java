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
@Table(name = "operator_recovery_code")
public class OperatorRecoveryCodeEntity {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false) private OperatorEntity operator;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "factor_id", nullable = false) private OperatorTotpFactorEntity factor;
    @Column(name = "batch_id", nullable = false) private UUID batchId;
    @Column(name = "code_hmac", nullable = false, unique = true, columnDefinition = "bytea") private byte[] codeHmac;
    @Column(name = "hmac_key_version", nullable = false, length = 32) private String hmacKeyVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "revocation_reason", length = 40) private String revocationReason;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public OperatorTotpFactorEntity getFactor() { return factor; }
    public void setFactor(OperatorTotpFactorEntity value) { factor = value; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID value) { batchId = value; }
    public byte[] getCodeHmac() { return codeHmac == null ? null : codeHmac.clone(); }
    public void setCodeHmac(byte[] value) { codeHmac = value == null ? null : value.clone(); }
    public String getHmacKeyVersion() { return hmacKeyVersion; }
    public void setHmacKeyVersion(String value) { hmacKeyVersion = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { consumedAt = value; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant value) { revokedAt = value; }
    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String value) { revocationReason = value; }
    public long getRowVersion() { return rowVersion; }
}
