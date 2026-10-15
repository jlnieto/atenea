package com.atenea.delivery;

import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialEntity;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReleasePasskeyTrustStore {
    private final JdbcTemplate jdbc;
    private final OperatorWebAuthnCredentialRepository credentials;

    public ReleasePasskeyTrustStore(JdbcTemplate jdbc, OperatorWebAuthnCredentialRepository credentials) {
        this.jdbc = jdbc;
        this.credentials = credentials;
    }

    @Transactional(readOnly = true)
    public Set<String> trustedCredentialIds(Long operatorId) {
        return credentials.findAllByOperatorIdAndRevokedAtIsNullOrderByLabelOrdinalAscIdAsc(operatorId).stream()
                .filter(this::trusted).map(value -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.getCredentialId()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true)
    public boolean isTrusted(Long operatorId, String encodedId) {
        try {
            return credentials.findByCredentialId(Base64.getUrlDecoder().decode(encodedId))
                    .filter(value -> value.getRevokedAt() == null && value.getOperator().getId().equals(operatorId))
                    .filter(this::trusted).isPresent();
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private boolean trusted(OperatorWebAuthnCredentialEntity credential) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM operator_release_passkey_trust t
              JOIN operator_privileged_action_authorization a ON a.id=t.authorization_id AND a.operator_id=t.operator_id
              WHERE t.credential_record_id=? AND t.operator_id=? AND t.credential_fingerprint_sha256=?
                AND a.factor='TOTP' AND a.action_kind='TRUST_RELEASE_PASSKEY' AND a.consumed_at IS NOT NULL)
            """, Boolean.class, credential.getId(), credential.getOperator().getId(), fingerprint(credential)));
    }

    /** Called inside the existing grant acceptance transaction, with this credential locked. */
    public void approve(OperatorWebAuthnCredentialEntity credential, UUID authorization, UUID verification) {
        byte[] digest = sha256(authorization.toString().getBytes(StandardCharsets.US_ASCII));
        int inserted = jdbc.update("""
            INSERT INTO operator_release_passkey_trust
              (credential_record_id,operator_id,credential_fingerprint_sha256,authorization_id,verification_request_id)
            SELECT ?,a.operator_id,?,a.id,? FROM operator_privileged_action_authorization a
              WHERE a.authorization_digest=? AND a.operator_id=? AND a.factor='TOTP'
                AND a.action_kind='TRUST_RELEASE_PASSKEY'
                AND EXISTS(SELECT 1 FROM operator_webauthn_challenge c WHERE c.id=? AND c.operator_id=a.operator_id
                  AND c.session_family_id=a.session_family_id AND c.channel='ANDROID' AND c.purpose='STEP_UP'
                  AND c.action_kind='SETUP_RELEASE_PASSKEY' AND c.consumed_at IS NOT NULL)
            ON CONFLICT (credential_record_id) DO NOTHING
            """, credential.getId(), fingerprint(credential), verification, digest, credential.getOperator().getId(), verification);
        if (inserted != 1 && !trusted(credential)) throw new OperatorAuthenticationException("Passkey trust rejected");
    }

    static String fingerprint(OperatorWebAuthnCredentialEntity credential) {
        byte[] id = credential.getCredentialId();
        byte[] key = credential.getPublicKeyCose();
        ByteBuffer data = ByteBuffer.allocate(4 + id.length + key.length);
        data.putInt(id.length).put(id).put(key);
        return HexFormat.of().formatHex(sha256(data.array()));
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
