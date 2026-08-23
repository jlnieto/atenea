package com.atenea.persistence.auth;

import com.atenea.auth.webauthn.WebAuthnProviderCategory;
import com.atenea.auth.webauthn.WebAuthnProviderProvenance;
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
@Table(name = "operator_webauthn_credential")
public class OperatorWebAuthnCredentialEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private OperatorEntity operator;

    @Column(name = "credential_id", nullable = false, unique = true, columnDefinition = "bytea")
    private byte[] credentialId;

    @Column(name = "public_key_cose", nullable = false, columnDefinition = "bytea")
    private byte[] publicKeyCose;

    @Column(nullable = false)
    private int algorithm;

    @Column(nullable = false)
    private UUID aaguid;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(nullable = false, length = 200)
    private String transports;

    @Column(name = "backup_eligible", nullable = false)
    private boolean backupEligible;

    @Column(name = "backup_state", nullable = false)
    private boolean backupState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 40)
    private String revocationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_category", nullable = false, length = 32)
    private WebAuthnProviderCategory providerCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_provenance", nullable = false, length = 32)
    private WebAuthnProviderProvenance providerProvenance;

    @Column(name = "label_ordinal", nullable = false)
    private long labelOrdinal;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public byte[] getCredentialId() { return credentialId == null ? null : credentialId.clone(); }
    public void setCredentialId(byte[] value) { credentialId = value == null ? null : value.clone(); }
    public byte[] getPublicKeyCose() { return publicKeyCose == null ? null : publicKeyCose.clone(); }
    public void setPublicKeyCose(byte[] value) { publicKeyCose = value == null ? null : value.clone(); }
    public int getAlgorithm() { return algorithm; }
    public void setAlgorithm(int value) { algorithm = value; }
    public UUID getAaguid() { return aaguid; }
    public void setAaguid(UUID value) { aaguid = value; }
    public long getSignCount() { return signCount; }
    public void setSignCount(long value) { signCount = value; }
    public String getTransports() { return transports; }
    public void setTransports(String value) { transports = value; }
    public boolean isBackupEligible() { return backupEligible; }
    public void setBackupEligible(boolean value) { backupEligible = value; }
    public boolean isBackupState() { return backupState; }
    public void setBackupState(boolean value) { backupState = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant value) { lastUsedAt = value; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant value) { revokedAt = value; }
    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String value) { revocationReason = value; }
    public WebAuthnProviderCategory getProviderCategory() { return providerCategory; }
    public void setProviderCategory(WebAuthnProviderCategory value) { providerCategory = value; }
    public WebAuthnProviderProvenance getProviderProvenance() { return providerProvenance; }
    public void setProviderProvenance(WebAuthnProviderProvenance value) { providerProvenance = value; }
    public long getLabelOrdinal() { return labelOrdinal; }
    public void setLabelOrdinal(long value) { labelOrdinal = value; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant value) { lastVerifiedAt = value; }
    public long getRowVersion() { return rowVersion; }
}
