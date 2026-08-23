package com.atenea.auth.webauthn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.recovery.TotpFactorState;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRecoveryCodeEntity;
import com.atenea.persistence.auth.OperatorRecoveryCodeRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorTotpFactorEntity;
import com.atenea.persistence.auth.OperatorTotpFactorRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialEntity;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "atenea.auth.webauthn.enabled=true",
        "atenea.auth.webauthn.credential-lifecycle-enabled=true",
        "atenea.auth.webauthn.credential-inventory-enabled=true",
        "atenea.auth.webauthn.credential-signalling-enabled=false",
        "atenea.auth.webauthn.restricted-ceremonies-enabled=true",
        "atenea.auth.webauthn.controlled-reset-enabled=false"
})
class WebAuthnControlledResetServiceIntegrationTest {

    @Autowired private WebAuthnControlledResetService service;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private OperatorWebAuthnCredentialRepository credentialRepository;
    @Autowired private OperatorTotpFactorRepository totpFactorRepository;
    @Autowired private OperatorRecoveryCodeRepository recoveryCodeRepository;
    @Autowired private OperatorAuthProperties properties;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private OperatorEntity operator;
    private AuthenticatedOperator actor;
    private List<OperatorWebAuthnCredentialEntity> historical;
    private OperatorWebAuthnCredentialEntity candidate;

    @BeforeEach
    void setUp() {
        clearSyntheticState();
        setResetFlags(false, true);
        operator = createOperator("reset");
        actor = authenticated(operator);
        historical = seedHistorical(operator, 2);
        seedRecoveryChannels(operator);
    }

    @AfterEach
    void tearDown() {
        setResetFlags(false, true);
        clearSyntheticState();
    }

    @Test
    void disabledDefaultPerformsNoInspectionOrMutation() {
        WebAuthnControlledResetStatus status = service.status(actor);

        assertEquals(WebAuthnControlledResetState.DISABLED, status.state());
        assertEquals("1Password", status.targetProvider());
        assertEquals(null, status.observedHistoricalCredentialCount());
        assertThrows(OperatorAuthenticationException.class,
                () -> service.commit(actor, UUID.randomUUID()));
        assertEquals(2L, activeHistoricalCount(operator.getId()));
        assertEquals(6L, credentialVersion(operator.getId()));
    }

    @Test
    void contradictoryResetFlagsFailClosed() {
        setResetFlags(true, false);

        assertThrows(WebAuthnLifecycleUnavailableException.class,
                () -> service.status(actor));
        assertEquals(2L, activeHistoricalCount(operator.getId()));
    }

    @Test
    void verifiedOnePasswordCandidateRevokesHistoricalRowsAtomicallyAndPreservesRecovery() {
        candidate = seedCandidate(operator, true, WebAuthnProviderCategory.ONE_PASSWORD);
        List<UUID> totpIds = activeTotpIds(operator.getId());
        List<UUID> recoveryIds = activeRecoveryIds(operator.getId());
        setResetFlags(true, true);

        WebAuthnControlledResetStatus before = service.status(actor);
        assertEquals(WebAuthnControlledResetState.COMMIT_READY, before.state());
        assertEquals(candidate.getId(), before.candidateRecordId());
        assertEquals(4, before.observedHistoricalCredentialCount());
        assertEquals(1, before.activeTotpCount());
        assertEquals(10, before.activeRecoveryCodeCount());

        WebAuthnControlledResetResult result = service.commit(actor, candidate.getId());

        assertEquals("COMMITTED", result.state());
        assertEquals(1, result.activePasskeyCount());
        assertEquals(4, result.revokedHistoricalCount());
        assertEquals(1, result.activeTotpCount());
        assertEquals(10, result.activeRecoveryCodeCount());
        assertEquals(7L, result.credentialVersion());
        assertEquals(0L, activeHistoricalCount(operator.getId()));
        assertEquals(1L, activeCredentialCount(operator.getId()));
        assertEquals(5L, credentialRepository.count());
        assertEquals(totpIds, activeTotpIds(operator.getId()));
        assertEquals(recoveryIds, activeRecoveryIds(operator.getId()));
        assertEquals("HISTORICAL_ALREADY_REVOKED",
                credentialRepository.findById(historical.get(2).getId()).orElseThrow()
                        .getRevocationReason());
        assertEquals(WebAuthnControlledResetState.COMPLETE, service.status(actor).state());
    }

    @Test
    void missingProofWrongProviderCardinalityAndCrossOperatorAreRejectedWithoutPartialRevocation() {
        setResetFlags(true, true);
        candidate = seedCandidate(operator, false, WebAuthnProviderCategory.ONE_PASSWORD);
        assertEquals(WebAuthnControlledResetState.PROVE_NEW, service.status(actor).state());
        assertThrows(OperatorAuthenticationException.class,
                () -> service.commit(actor, candidate.getId()));
        assertEquals(2L, activeHistoricalCount(operator.getId()));

        candidate.setLastUsedAt(Instant.now());
        candidate.setLastVerifiedAt(candidate.getLastUsedAt());
        candidate.setProviderCategory(WebAuthnProviderCategory.UNKNOWN);
        candidate.setProviderProvenance(WebAuthnProviderProvenance.UNKNOWN);
        credentialRepository.saveAndFlush(candidate);
        assertEquals(WebAuthnControlledResetState.BLOCKED, service.status(actor).state());
        assertThrows(OperatorAuthenticationException.class,
                () -> service.commit(actor, candidate.getId()));

        OperatorEntity foreign = createOperator("foreign");
        OperatorWebAuthnCredentialEntity foreignCandidate = seedCandidate(
                foreign, true, WebAuthnProviderCategory.ONE_PASSWORD);
        assertThrows(OperatorAuthenticationException.class,
                () -> service.commit(actor, foreignCandidate.getId()));
        assertEquals(2L, activeHistoricalCount(operator.getId()));
        assertEquals(1L, activeCredentialCount(foreign.getId()));
    }

    @Test
    void concurrentCommitHasExactlyOneWinner() throws Exception {
        candidate = seedCandidate(operator, true, WebAuthnProviderCategory.ONE_PASSWORD);
        setResetFlags(true, true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.commit(actor, candidate.getId());
                        return true;
                    } catch (OperatorAuthenticationException exception) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) winners++;
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0L, activeHistoricalCount(operator.getId()));
        assertEquals(1L, activeCredentialCount(operator.getId()));
        assertEquals(7L, credentialVersion(operator.getId()));
    }

    @Test
    void credentialVersionOverflowRollsBackHistoricalRevocation() {
        candidate = seedCandidate(operator, true, WebAuthnProviderCategory.ONE_PASSWORD);
        jdbcTemplate.update(
                "UPDATE operator_account SET credential_version = ? WHERE id = ?",
                Long.MAX_VALUE,
                operator.getId());
        setResetFlags(true, true);

        assertThrows(OperatorAuthenticationException.class,
                () -> service.commit(actor, candidate.getId()));

        assertEquals(2L, activeHistoricalCount(operator.getId()));
        assertEquals(3L, activeCredentialCount(operator.getId()));
        assertEquals(Long.MAX_VALUE, credentialVersion(operator.getId()));
        assertEquals(1, activeTotpIds(operator.getId()).size());
        assertEquals(10, activeRecoveryIds(operator.getId()).size());
    }

    private OperatorEntity createOperator(String prefix) {
        OperatorEntity value = new OperatorEntity();
        value.setEmail(prefix + "-" + UUID.randomUUID() + "@atenea.test");
        value.setDisplayName("Synthetic reset operator");
        value.setPasswordHash(passwordEncoder.encode("synthetic-password"));
        value.setActive(true);
        value.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        value.setCredentialVersion(6L);
        value.setCreatedAt(Instant.now().minusSeconds(300));
        value.setUpdatedAt(Instant.now().minusSeconds(300));
        return operatorRepository.saveAndFlush(value);
    }

    private AuthenticatedOperator authenticated(OperatorEntity value) {
        return new AuthenticatedOperator(
                value.getId(), value.getEmail(), value.getDisplayName());
    }

    private List<OperatorWebAuthnCredentialEntity> seedHistorical(
            OperatorEntity owner,
            int activeCount
    ) {
        List<OperatorWebAuthnCredentialEntity> values = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            OperatorWebAuthnCredentialEntity credential = credential(
                    owner,
                    index + 1,
                    WebAuthnProviderCategory.UNKNOWN,
                    WebAuthnProviderProvenance.UNKNOWN,
                    false);
            if (index >= activeCount) {
                credential.setRevokedAt(Instant.now().minusSeconds(120 - index));
                credential.setRevocationReason("HISTORICAL_ALREADY_REVOKED");
            }
            values.add(credentialRepository.saveAndFlush(credential));
        }
        return values;
    }

    private OperatorWebAuthnCredentialEntity seedCandidate(
            OperatorEntity owner,
            boolean verified,
            WebAuthnProviderCategory provider
    ) {
        WebAuthnProviderProvenance provenance = provider == WebAuthnProviderCategory.ONE_PASSWORD
                ? WebAuthnProviderProvenance.OPERATOR_DECLARED
                : WebAuthnProviderProvenance.UNKNOWN;
        OperatorWebAuthnCredentialEntity value = credential(
                owner, 5, provider, provenance, true);
        if (verified) {
            Instant now = Instant.now();
            value.setLastUsedAt(now);
            value.setLastVerifiedAt(now);
        }
        return credentialRepository.saveAndFlush(value);
    }

    private OperatorWebAuthnCredentialEntity credential(
            OperatorEntity owner,
            int ordinal,
            WebAuthnProviderCategory provider,
            WebAuthnProviderProvenance provenance,
            boolean backupEligible
    ) {
        OperatorWebAuthnCredentialEntity value = new OperatorWebAuthnCredentialEntity();
        value.setId(UUID.randomUUID());
        value.setOperator(owner);
        byte[] credentialId = bytes(32, ordinal);
        java.nio.ByteBuffer.wrap(credentialId).putLong(owner.getId());
        value.setCredentialId(credentialId);
        value.setPublicKeyCose(bytes(64, ordinal + 20));
        value.setAlgorithm(-7);
        value.setAaguid(new UUID(0L, ordinal));
        value.setSignCount(0L);
        value.setTransports("internal");
        value.setBackupEligible(backupEligible);
        value.setBackupState(backupEligible);
        value.setCreatedAt(Instant.now().minusSeconds(240 - ordinal));
        value.setProviderCategory(provider);
        value.setProviderProvenance(provenance);
        value.setLabelOrdinal(ordinal);
        return value;
    }

    private void seedRecoveryChannels(OperatorEntity owner) {
        Instant createdAt = Instant.now().minusSeconds(180);
        OperatorTotpFactorEntity factor = new OperatorTotpFactorEntity();
        factor.setId(UUID.randomUUID());
        factor.setOperator(owner);
        factor.setEnrollmentId(UUID.randomUUID());
        factor.setEncryptedSecret(bytes(44, 41));
        factor.setSecretKeyVersion("synthetic-v2");
        factor.setState(TotpFactorState.ACTIVE);
        factor.setCreatedAt(createdAt);
        factor.setExpiresAt(createdAt.plusSeconds(600));
        factor.setActivatedAt(createdAt.plusSeconds(1));
        factor = totpFactorRepository.saveAndFlush(factor);
        UUID batchId = UUID.randomUUID();
        for (int index = 0; index < 10; index++) {
            OperatorRecoveryCodeEntity code = new OperatorRecoveryCodeEntity();
            code.setId(UUID.randomUUID());
            code.setOperator(owner);
            code.setFactor(factor);
            code.setBatchId(batchId);
            code.setCodeHmac(bytes(32, 80 + index));
            code.setHmacKeyVersion("synthetic-v2");
            code.setCreatedAt(createdAt.plusSeconds(index + 2));
            recoveryCodeRepository.saveAndFlush(code);
        }
    }

    private byte[] bytes(int length, int marker) {
        byte[] value = new byte[length];
        java.util.Arrays.fill(value, (byte) marker);
        return value;
    }

    private long activeHistoricalCount(Long operatorId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_webauthn_credential
                WHERE operator_id = ? AND label_ordinal <= 4 AND revoked_at IS NULL
                """, Long.class, operatorId);
    }

    private long activeCredentialCount(Long operatorId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_webauthn_credential
                WHERE operator_id = ? AND revoked_at IS NULL
                """, Long.class, operatorId);
    }

    private long credentialVersion(Long operatorId) {
        return jdbcTemplate.queryForObject(
                "SELECT credential_version FROM operator_account WHERE id = ?",
                Long.class,
                operatorId);
    }

    private List<UUID> activeTotpIds(Long operatorId) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM operator_totp_factor
                WHERE operator_id = ? AND state = 'ACTIVE' AND revoked_at IS NULL
                ORDER BY id
                """, UUID.class, operatorId);
    }

    private List<UUID> activeRecoveryIds(Long operatorId) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM operator_recovery_code
                WHERE operator_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                ORDER BY id
                """, UUID.class, operatorId);
    }

    private void setResetFlags(boolean reset, boolean restricted) {
        properties.getWebAuthn().setEnabled(true);
        properties.getWebAuthn().setCredentialLifecycleEnabled(true);
        properties.getWebAuthn().setCredentialInventoryEnabled(true);
        properties.getWebAuthn().setCredentialSignallingEnabled(false);
        properties.getWebAuthn().setRestrictedCeremoniesEnabled(restricted);
        properties.getWebAuthn().setControlledResetEnabled(reset);
    }

    private void clearSyntheticState() {
        jdbcTemplate.update("DELETE FROM operator_security_notification");
        jdbcTemplate.update("DELETE FROM operator_security_event");
        jdbcTemplate.update("DELETE FROM operator_auth_attempt_window");
        jdbcTemplate.update("DELETE FROM operator_recovery_code");
        jdbcTemplate.update("DELETE FROM operator_totp_factor");
        jdbcTemplate.update("DELETE FROM operator_webauthn_challenge");
        jdbcTemplate.update("DELETE FROM operator_webauthn_credential");
        jdbcTemplate.update("DELETE FROM operator_webauthn_user");
        jdbcTemplate.update("DELETE FROM operator_refresh_token");
        jdbcTemplate.update("DELETE FROM operator_session_family");
        jdbcTemplate.update("DELETE FROM operator_account WHERE email LIKE '%@atenea.test'");
    }
}
