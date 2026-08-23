package com.atenea.persistence.auth;

import com.atenea.auth.action.PrivilegedActionFactor;
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
@Table(name = "operator_privileged_action_authorization")
public class OperatorPrivilegedActionAuthorizationEntity {
    @Id private UUID id;
    @Column(name = "authorization_digest", nullable = false, unique = true, columnDefinition = "bytea")
    private byte[] authorizationDigest;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false) private OperatorEntity operator;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_family_id", nullable = false) private OperatorSessionFamilyEntity sessionFamily;
    @Column(name = "action_kind", nullable = false, length = 64) private String actionKind;
    @Column(name = "target_fingerprint", nullable = false, columnDefinition = "bytea") private byte[] targetFingerprint;
    @Column(name = "plan_fingerprint", nullable = false, columnDefinition = "bytea") private byte[] planFingerprint;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PrivilegedActionFactor factor;
    @Column(name = "authenticated_at", nullable = false) private Instant authenticatedAt;
    @Column(name = "credential_version", nullable = false) private long credentialVersion;
    @Column(name = "role_version", nullable = false) private long roleVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public byte[] getAuthorizationDigest() { return clone(authorizationDigest); }
    public void setAuthorizationDigest(byte[] value) { authorizationDigest = clone(value); }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public OperatorSessionFamilyEntity getSessionFamily() { return sessionFamily; }
    public void setSessionFamily(OperatorSessionFamilyEntity value) { sessionFamily = value; }
    public String getActionKind() { return actionKind; }
    public void setActionKind(String value) { actionKind = value; }
    public byte[] getTargetFingerprint() { return clone(targetFingerprint); }
    public void setTargetFingerprint(byte[] value) { targetFingerprint = clone(value); }
    public byte[] getPlanFingerprint() { return clone(planFingerprint); }
    public void setPlanFingerprint(byte[] value) { planFingerprint = clone(value); }
    public PrivilegedActionFactor getFactor() { return factor; }
    public void setFactor(PrivilegedActionFactor value) { factor = value; }
    public Instant getAuthenticatedAt() { return authenticatedAt; }
    public void setAuthenticatedAt(Instant value) { authenticatedAt = value; }
    public long getCredentialVersion() { return credentialVersion; }
    public void setCredentialVersion(long value) { credentialVersion = value; }
    public long getRoleVersion() { return roleVersion; }
    public void setRoleVersion(long value) { roleVersion = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { consumedAt = value; }
    public long getRowVersion() { return rowVersion; }
    private static byte[] clone(byte[] value) { return value == null ? null : value.clone(); }
}
