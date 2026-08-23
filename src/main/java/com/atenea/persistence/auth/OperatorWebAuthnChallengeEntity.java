package com.atenea.persistence.auth;

import com.atenea.auth.webauthn.WebAuthnChallengePurpose;
import com.atenea.auth.webauthn.WebAuthnChannel;
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
@Table(name = "operator_webauthn_challenge")
public class OperatorWebAuthnChallengeEntity {

    @Id
    private UUID id;

    @Column(name = "challenge_digest", nullable = false, unique = true, columnDefinition = "bytea")
    private byte[] challengeDigest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebAuthnChallengePurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private WebAuthnChannel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id")
    private OperatorEntity operator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_family_id")
    private OperatorSessionFamilyEntity sessionFamily;

    @Column(name = "relying_party_id", nullable = false, length = 253)
    private String relyingPartyId;

    @Column(name = "expected_origin", nullable = false, length = 512)
    private String expectedOrigin;

    @Column(name = "action_kind", length = 64)
    private String actionKind;

    @Column(name = "target_fingerprint", columnDefinition = "bytea")
    private byte[] targetFingerprint;

    @Column(name = "plan_fingerprint", columnDefinition = "bytea")
    private byte[] planFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public byte[] getChallengeDigest() { return challengeDigest == null ? null : challengeDigest.clone(); }
    public void setChallengeDigest(byte[] value) { challengeDigest = value == null ? null : value.clone(); }
    public WebAuthnChallengePurpose getPurpose() { return purpose; }
    public void setPurpose(WebAuthnChallengePurpose value) { purpose = value; }
    public WebAuthnChannel getChannel() { return channel; }
    public void setChannel(WebAuthnChannel value) { channel = value; }
    public OperatorEntity getOperator() { return operator; }
    public void setOperator(OperatorEntity value) { operator = value; }
    public OperatorSessionFamilyEntity getSessionFamily() { return sessionFamily; }
    public void setSessionFamily(OperatorSessionFamilyEntity value) { sessionFamily = value; }
    public String getRelyingPartyId() { return relyingPartyId; }
    public void setRelyingPartyId(String value) { relyingPartyId = value; }
    public String getExpectedOrigin() { return expectedOrigin; }
    public void setExpectedOrigin(String value) { expectedOrigin = value; }
    public String getActionKind() { return actionKind; }
    public void setActionKind(String value) { actionKind = value; }
    public byte[] getTargetFingerprint() { return targetFingerprint == null ? null : targetFingerprint.clone(); }
    public void setTargetFingerprint(byte[] value) { targetFingerprint = value == null ? null : value.clone(); }
    public byte[] getPlanFingerprint() { return planFingerprint == null ? null : planFingerprint.clone(); }
    public void setPlanFingerprint(byte[] value) { planFingerprint = value == null ? null : value.clone(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant value) { consumedAt = value; }
    public long getRowVersion() { return rowVersion; }
}
