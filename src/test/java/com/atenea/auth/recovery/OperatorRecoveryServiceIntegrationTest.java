package com.atenea.auth.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.JwtTokenService;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.MobileLoginRequest;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.RefreshTokenService;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.action.PrivilegedActionFactor;
import com.atenea.auth.action.VerifiedStepUp;
import com.atenea.auth.session.SessionVersions;
import com.atenea.auth.webauthn.WebAuthnChannel;
import com.atenea.auth.webauthn.WebAuthnService;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest(properties = {
        "atenea.auth.recovery.totp-enabled=true",
        "atenea.auth.recovery.recovery-enabled=true",
        "atenea.auth.recovery.enforcement-enabled=false"
})
@ExtendWith(OutputCaptureExtension.class)
class OperatorRecoveryServiceIntegrationTest {
    private static final String PASSWORD = "synthetic-m122-password";
    private static final String ENCRYPTION_VERSION = "enc1";
    private static final String HMAC_VERSION = "mac1";
    private static final String ENCRYPTION_KEY = key((byte) 0x31);
    private static final String HMAC_KEY = key((byte) 0x57);

    @Autowired private OperatorRecoveryService recoveryService;
    @Autowired private OperatorAuthenticationService authenticationService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private WebAuthnService webAuthnService;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private OperatorAuthProperties properties;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private OperatorEntity operator;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        clearSyntheticState();
        configureValidKeys();
        properties.getRecovery().setTotpEnabled(true);
        properties.getRecovery().setRecoveryEnabled(true);
        properties.getRecovery().setEnforcementEnabled(false);
        properties.getRecovery().setEnrollmentTtl(Duration.ofMinutes(10));
        properties.getRecovery().setAttemptWindow(Duration.ofMinutes(5));
        properties.getRecovery().setLockout(Duration.ofMinutes(15));
        properties.getRecovery().setMaxAttempts(5);
        properties.getWebAuthn().setEnabled(true);
        operator = syntheticOperator("primary");
        actor = authenticated(operator);
    }

    @AfterEach
    void tearDown() {
        properties.getRecovery().setTotpEnabled(false);
        properties.getRecovery().setRecoveryEnabled(false);
        properties.getRecovery().setEnforcementEnabled(false);
        properties.getRecovery().setEncryptionKeys(List.of());
        properties.getRecovery().setHmacKeys(List.of());
        properties.getRecovery().setActiveEncryptionKeyVersion(null);
        properties.getRecovery().setActiveHmacKeyVersion(null);
        properties.getWebAuthn().setEnabled(false);
        clearSyntheticState();
    }

    @Test
    void enrollmentIsTwoPhaseEncryptedAndIssuesTenOneTimeCodes() {
        RefreshTokenService.IssuedSession oldSession = refreshTokenService.createFamilySession(
                operator, "WEB", "Synthetic browser");
        String oldAccessToken = accessToken(operator, actor, oldSession);

        TotpEnrollmentStartResponse started = recoveryService.beginTotpEnrollment(actor);
        byte[] rawSecret = decodeBase32(started.secret());
        assertEquals(20, rawSecret.length);
        assertEquals("SHA1", started.algorithm());
        assertEquals(6, started.digits());
        assertEquals(30, started.periodSeconds());
        assertFalse(started.toString().contains(started.secret()));
        byte[] encrypted = jdbcTemplate.queryForObject("""
                SELECT encrypted_secret FROM operator_totp_factor WHERE enrollment_id = ?
                """, byte[].class, started.enrollmentId());
        assertNotNull(encrypted);
        assertNotEquals(Base64.getEncoder().encodeToString(rawSecret),
                Base64.getEncoder().encodeToString(encrypted));
        assertEquals("PENDING", factorState(started.enrollmentId()));

        String code = currentCode(rawSecret, 0);
        TotpEnrollmentActivationResponse activated = recoveryService.activateTotpEnrollment(
                actor, new TotpEnrollmentActivationRequest(started.enrollmentId(), code));
        assertEquals(10, activated.recoveryCodes().size());
        assertEquals(10, new HashSet<>(activated.recoveryCodes()).size());
        assertTrue(activated.recoveryCodes().stream().allMatch(value -> value.length() == 22));
        assertFalse(activated.toString().contains(activated.recoveryCodes().get(0)));
        assertEquals("ACTIVE", factorState(started.enrollmentId()));
        assertEquals(10, count("SELECT count(*) FROM operator_recovery_code"));
        assertEquals(10, count("""
                SELECT count(*) FROM operator_recovery_code
                WHERE octet_length(code_hmac) = 32 AND hmac_key_version = 'mac1'
                """));
        for (String rawCode : activated.recoveryCodes()) {
            assertEquals(0, count("""
                    SELECT count(*) FROM operator_recovery_code
                    WHERE encode(code_hmac, 'base64') = ?
                    """, rawCode));
        }
        assertEquals(1L, credentialVersion(operator.getId()));
        assertThrows(OperatorAuthenticationException.class,
                () -> authenticationService.authenticateAccessToken(oldAccessToken));
        Arrays.fill(rawSecret, (byte) 0);
    }

    @Test
    void supportsCancellationExpiryAllowedWindowsAndCounterReuseProtection() {
        TotpEnrollmentStartResponse cancelled = recoveryService.beginTotpEnrollment(actor);
        recoveryService.cancelTotpEnrollment(actor, cancelled.enrollmentId());
        assertEquals("CANCELLED", factorState(cancelled.enrollmentId()));

        TotpEnrollmentStartResponse expired = recoveryService.beginTotpEnrollment(actor);
        byte[] expiredSecret = decodeBase32(expired.secret());
        jdbcTemplate.update("""
                UPDATE operator_totp_factor
                SET created_at = now() - interval '20 minutes',
                    expires_at = now() - interval '1 second'
                WHERE enrollment_id = ?
                """, expired.enrollmentId());
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(
                        expired.enrollmentId(), currentCode(expiredSecret, 0))));
        assertEquals("EXPIRED", factorState(expired.enrollmentId()));

        for (int offset : List.of(-1, 0, 1)) {
            TotpEnrollmentStartResponse start = recoveryService.beginTotpEnrollment(actor);
            byte[] secret = decodeBase32(start.secret());
            recoveryService.activateTotpEnrollment(actor,
                    new TotpEnrollmentActivationRequest(
                            start.enrollmentId(), currentCode(secret, offset)));
            assertEquals("ACTIVE", factorState(start.enrollmentId()));
            Arrays.fill(secret, (byte) 0);
        }

        TotpEnrollmentStartResponse outside = recoveryService.beginTotpEnrollment(actor);
        byte[] outsideSecret = decodeBase32(outside.secret());
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(
                        outside.enrollmentId(), currentCode(outsideSecret, 2))));
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(outside.enrollmentId(), "12x456")));

        TotpEnrollmentStartResponse reuse = recoveryService.beginTotpEnrollment(actor);
        byte[] reuseSecret = decodeBase32(reuse.secret());
        String activationCode = currentCode(reuseSecret, 0);
        recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(reuse.enrollmentId(), activationCode));
        assertRejected(() -> recoveryService.removeTotpFactor(
                actor, new TotpFactorRemovalRequest(activationCode)));
        recoveryService.removeTotpFactor(
                actor, new TotpFactorRemovalRequest(currentCode(reuseSecret, 1)));
        assertEquals("REVOKED", factorState(reuse.enrollmentId()));
        assertTrue(factorReenrollmentRequired(operator.getId()));
    }

    @Test
    void keyConfigurationAndDurableAttemptLimitsFailClosed() {
        List<Runnable> invalidConfigurations = List.of(
                () -> properties.getRecovery().setEncryptionKeys(List.of()),
                () -> properties.getRecovery().setEncryptionKeys(List.of(
                        ENCRYPTION_VERSION + ":" + ENCRYPTION_KEY,
                        ENCRYPTION_VERSION + ":" + key((byte) 0x32))),
                () -> properties.getRecovery().setEncryptionKeys(List.of(
                        ENCRYPTION_VERSION + ":not-base64")),
                () -> properties.getRecovery().setActiveEncryptionKeyVersion("unknown"),
                () -> properties.getRecovery().setHmacKeys(List.of(
                        HMAC_VERSION + ":" + HMAC_KEY,
                        "mac2:" + HMAC_KEY)));
        for (Runnable mutate : invalidConfigurations) {
            configureValidKeys();
            mutate.run();
            assertRejected(() -> recoveryService.beginTotpEnrollment(actor));
        }
        configureValidKeys();
        properties.getRecovery().setMaxAttempts(2);
        TotpEnrollmentStartResponse start = recoveryService.beginTotpEnrollment(actor);
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(start.enrollmentId(), "000000")));
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(start.enrollmentId(), "000000")));
        byte[] secret = decodeBase32(start.secret());
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(
                        start.enrollmentId(), currentCode(secret, 0))));
        jdbcTemplate.update("""
                UPDATE operator_auth_attempt_window
                SET window_started_at = now() - interval '20 minutes',
                    blocked_until = now() - interval '1 minute', updated_at = now()
                WHERE operator_id = ? AND scope = 'TOTP_ENROLLMENT'
                """, operator.getId());
        TotpEnrollmentActivationResponse activated = recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(start.enrollmentId(), currentCode(secret, 0)));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_auth_attempt_window
                WHERE operator_id = ? AND scope = 'TOTP_ENROLLMENT'
                """, operator.getId()));

        String recoveryCode = activated.recoveryCodes().get(0);
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), "wrong-password", recoveryCode)));
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), "wrong-password", recoveryCode)));
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), PASSWORD, recoveryCode)));
        jdbcTemplate.update("""
                UPDATE operator_auth_attempt_window
                SET window_started_at = now() - interval '20 minutes',
                    blocked_until = now() - interval '1 minute', updated_at = now()
                WHERE operator_id = ? AND scope = 'RECOVERY'
                """, operator.getId());
        recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), PASSWORD, recoveryCode));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_auth_attempt_window
                WHERE operator_id = ? AND scope = 'RECOVERY'
                """, operator.getId()));
    }

    @Test
    void unknownKeyVersionsAndCrossOperatorCiphertextFailClosed() {
        TotpEnrollmentStartResponse unknownEncryption =
                recoveryService.beginTotpEnrollment(actor);
        byte[] unknownSecret = decodeBase32(unknownEncryption.secret());
        jdbcTemplate.update("""
                UPDATE operator_totp_factor SET secret_key_version = 'unknown'
                WHERE enrollment_id = ?
                """, unknownEncryption.enrollmentId());
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(
                        unknownEncryption.enrollmentId(), currentCode(unknownSecret, 0))));

        TotpEnrollmentActivationResponse codes = activateNewFactor(actor);
        jdbcTemplate.update("""
                UPDATE operator_recovery_code SET hmac_key_version = 'unknown'
                WHERE operator_id = ?
                """, operator.getId());
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), PASSWORD, codes.recoveryCodes().get(0))));

        OperatorEntity foreign = syntheticOperator("ciphertext-owner");
        AuthenticatedOperator foreignActor = authenticated(foreign);
        TotpEnrollmentStartResponse primaryStart = recoveryService.beginTotpEnrollment(actor);
        TotpEnrollmentStartResponse foreignStart =
                recoveryService.beginTotpEnrollment(foreignActor);
        byte[] foreignSecret = decodeBase32(foreignStart.secret());
        jdbcTemplate.update("""
                UPDATE operator_totp_factor
                SET encrypted_secret = (
                    SELECT encrypted_secret FROM operator_totp_factor
                    WHERE enrollment_id = ?)
                WHERE enrollment_id = ?
                """, foreignStart.enrollmentId(), primaryStart.enrollmentId());
        assertRejected(() -> recoveryService.activateTotpEnrollment(actor,
                new TotpEnrollmentActivationRequest(
                        primaryStart.enrollmentId(), currentCode(foreignSecret, 0))));
    }

    @Test
    void recoveryRequiresPasswordAndOwnedCodeThenRevokesEveryCredential() {
        TotpEnrollmentActivationResponse activated = activateNewFactor(actor);
        String selectedCode = activated.recoveryCodes().get(0);
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), "wrong-password", selectedCode)));

        RefreshTokenService.IssuedSession family = refreshTokenService.createFamilySession(
                operator, "WEB", "Recovery family");
        refreshTokenService.createLegacySession(operator);
        String accessToken = accessToken(operatorRepository.findById(operator.getId()).orElseThrow(),
                actor, family);
        insertSyntheticCredential(operator.getId());
        webAuthnService.beginRegistration(
                actor, family.familyId(), WebAuthnChannel.WEB, "https://atenea.yudri.es");

        recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), PASSWORD, selectedCode));

        assertEquals(1, count("""
                SELECT count(*) FROM operator_recovery_code WHERE consumed_at IS NOT NULL
                """));
        assertEquals(9, count("""
                SELECT count(*) FROM operator_recovery_code WHERE revoked_at IS NOT NULL
                """));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_session_family WHERE revoked_at IS NULL
                """));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_refresh_token WHERE revoked_at IS NULL
                """));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_webauthn_credential WHERE revoked_at IS NULL
                """));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_webauthn_challenge WHERE consumed_at IS NULL
                """));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_totp_factor WHERE state IN ('PENDING', 'ACTIVE')
                """));
        assertEquals(2L, credentialVersion(operator.getId()));
        assertTrue(factorReenrollmentRequired(operator.getId()));
        assertThrows(OperatorAuthenticationException.class,
                () -> authenticationService.authenticateAccessToken(accessToken));
        assertEquals(1, count("""
                SELECT count(*) FROM operator_security_event
                WHERE event_type = 'ACCOUNT_RECOVERED' AND outcome = 'SUCCEEDED'
                """));
        assertEquals(1, count("""
                SELECT count(*) FROM operator_security_notification
                WHERE template_code = 'ACCOUNT_RECOVERED' AND state = 'PENDING'
                """));
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), PASSWORD, selectedCode)));
    }

    @Test
    void recoveryIsOwnedAtomicConcurrentAndIsolated() throws Exception {
        TotpEnrollmentActivationResponse primaryCodes = activateNewFactor(actor);
        String primaryCode = primaryCodes.recoveryCodes().get(0);
        OperatorEntity foreign = syntheticOperator("foreign");
        AuthenticatedOperator foreignActor = authenticated(foreign);
        String foreignCode = activateNewFactor(foreignActor).recoveryCodes().get(0);
        RefreshTokenService.IssuedSession foreignSession = refreshTokenService.createFamilySession(
                foreign, "WEB", "Foreign family");

        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                foreign.getEmail(), PASSWORD, primaryCode)));
        assertRejected(() -> recoveryService.recover(new AccountRecoveryRequest(
                operator.getEmail(), PASSWORD, foreignCode)));
        assertEquals(1, count("""
                SELECT count(*) FROM operator_session_family
                WHERE operator_id = ? AND revoked_at IS NULL
                """, foreign.getId()));

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> consume = () -> {
            start.await();
            try {
                recoveryService.recover(new AccountRecoveryRequest(
                        operator.getEmail(), PASSWORD, primaryCode));
                return true;
            } catch (OperatorAuthenticationException exception) {
                return false;
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(consume);
            Future<Boolean> second = executor.submit(consume);
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        }
        assertTrue(jdbcTemplate.queryForObject("""
                SELECT revoked_at IS NULL FROM operator_session_family WHERE id = ?
                """, Boolean.class, foreignSession.familyId()));
        assertEquals(1L, credentialVersion(foreign.getId()));
    }

    @Test
    void totpStepUpIsBoundToLiveOwnedSessionAndRejectsCounterReuse() {
        RefreshTokenService.IssuedSession family = refreshTokenService.createFamilySession(
                operator, "WEB", "Synthetic step-up browser");
        TotpEnrollmentStartResponse start = recoveryService.beginTotpEnrollment(actor);
        byte[] secret = decodeBase32(start.secret());
        try {
            recoveryService.activateTotpEnrollment(actor,
                    new TotpEnrollmentActivationRequest(
                            start.enrollmentId(), currentCode(secret, 0)));
            PrivilegedActionBinding binding = PrivilegedActionBinding.fromCanonical(
                    "SYNTHETIC_ACTION", "target=owned", "plan=exact");
            String nextWindow = currentCode(secret, 1);
            VerifiedStepUp proof = recoveryService.verifyTotpStepUp(
                    actor, family.familyId(), binding, nextWindow);
            assertEquals(operator.getId(), proof.operatorId());
            assertEquals(family.familyId(), proof.sessionFamilyId());
            assertEquals(PrivilegedActionFactor.TOTP, proof.factor());
            assertRejected(() -> recoveryService.verifyTotpStepUp(
                    actor, family.familyId(), binding, nextWindow));

            OperatorEntity foreign = syntheticOperator("step-up-foreign");
            RefreshTokenService.IssuedSession foreignFamily =
                    refreshTokenService.createFamilySession(
                            foreign, "WEB", "Foreign step-up browser");
            assertRejected(() -> recoveryService.verifyTotpStepUp(
                    actor, foreignFamily.familyId(), binding, currentCode(secret, 1)));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Test
    void disabledFeaturesPreserveLegacyAndSensitiveValuesStayOutOfDiagnostics(
            CapturedOutput output
    ) {
        properties.getRecovery().setTotpEnabled(false);
        properties.getRecovery().setRecoveryEnabled(false);
        assertRejected(() -> recoveryService.beginTotpEnrollment(actor));
        MobileAuthSessionResponse legacy = authenticationService.login(new MobileLoginRequest(
                operator.getEmail(), PASSWORD, null, null, null, null));
        assertNotNull(legacy.accessToken());

        properties.getRecovery().setTotpEnabled(true);
        properties.getRecovery().setRecoveryEnabled(true);
        TotpEnrollmentStartResponse start = recoveryService.beginTotpEnrollment(actor);
        String secretMarker = start.secret();
        String passwordMarker = "private-password-marker";
        String codeMarker = "private-code-marker";
        AccountRecoveryRequest request = new AccountRecoveryRequest(
                operator.getEmail(), passwordMarker, codeMarker);
        OperatorAuthenticationException exception = assertThrows(
                OperatorAuthenticationException.class,
                () -> recoveryService.recover(request));
        assertEquals("Account recovery rejected", exception.getMessage());
        assertFalse(request.toString().contains(passwordMarker));
        assertFalse(request.toString().contains(codeMarker));
        assertFalse(start.toString().contains(secretMarker));
        assertFalse(output.getAll().contains(passwordMarker));
        assertFalse(output.getAll().contains(codeMarker));
        assertFalse(output.getAll().contains(secretMarker));
        assertEquals(0, count("""
                SELECT count(*) FROM operator_security_event
                WHERE event_type LIKE ? OR outcome LIKE ?
                """, "%" + codeMarker + "%", "%" + passwordMarker + "%"));
    }

    private TotpEnrollmentActivationResponse activateNewFactor(AuthenticatedOperator subject) {
        TotpEnrollmentStartResponse start = recoveryService.beginTotpEnrollment(subject);
        byte[] secret = decodeBase32(start.secret());
        try {
            return recoveryService.activateTotpEnrollment(subject,
                    new TotpEnrollmentActivationRequest(
                            start.enrollmentId(), currentCode(secret, 0)));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private String accessToken(
            OperatorEntity account,
            AuthenticatedOperator subject,
            RefreshTokenService.IssuedSession session
    ) {
        return jwtTokenService.issueAccessToken(
                subject,
                session.familyId(),
                new SessionVersions(
                        account.getCredentialVersion(), account.getRoleVersion()),
                session.authenticatedAt(),
                session.authenticationMethods()).token();
    }

    private OperatorEntity syntheticOperator(String marker) {
        Instant now = Instant.now();
        OperatorEntity value = new OperatorEntity();
        value.setEmail("m122-" + marker + "-" + UUID.randomUUID() + "@atenea.test");
        value.setDisplayName("Synthetic M1.2.2 operator");
        value.setPasswordHash(passwordEncoder.encode(PASSWORD));
        value.setActive(true);
        value.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return operatorRepository.saveAndFlush(value);
    }

    private AuthenticatedOperator authenticated(OperatorEntity value) {
        return new AuthenticatedOperator(value.getId(), value.getEmail(), value.getDisplayName());
    }

    private void configureValidKeys() {
        properties.getRecovery().setEncryptionKeys(
                List.of(ENCRYPTION_VERSION + ":" + ENCRYPTION_KEY));
        properties.getRecovery().setActiveEncryptionKeyVersion(ENCRYPTION_VERSION);
        properties.getRecovery().setHmacKeys(List.of(HMAC_VERSION + ":" + HMAC_KEY));
        properties.getRecovery().setActiveHmacKeyVersion(HMAC_VERSION);
    }

    private void insertSyntheticCredential(Long operatorId) {
        jdbcTemplate.update("""
                INSERT INTO operator_webauthn_credential (
                    id, operator_id, credential_id, public_key_cose, algorithm,
                    aaguid, sign_count, transports, backup_eligible, backup_state,
                    created_at, label_ordinal)
                VALUES (?, ?, ?, ?, -7, ?, 0, 'internal', FALSE, FALSE, now(), 1)
                """, UUID.randomUUID(), operatorId, filled(32, (byte) 0x23),
                filled(32, (byte) 0x45), new UUID(0, 0));
    }

    private String factorState(UUID enrollmentId) {
        return jdbcTemplate.queryForObject("""
                SELECT state FROM operator_totp_factor WHERE enrollment_id = ?
                """, String.class, enrollmentId);
    }

    private long credentialVersion(Long operatorId) {
        return jdbcTemplate.queryForObject("""
                SELECT credential_version FROM operator_account WHERE id = ?
                """, Long.class, operatorId);
    }

    private boolean factorReenrollmentRequired(Long operatorId) {
        return jdbcTemplate.queryForObject("""
                SELECT factor_reenrollment_required FROM operator_account WHERE id = ?
                """, Boolean.class, operatorId);
    }

    private int count(String sql, Object... values) {
        return jdbcTemplate.queryForObject(sql, Integer.class, values);
    }

    private String currentCode(byte[] secret, int offset) {
        long counter = Instant.now().getEpochSecond() / TotpAlgorithm.STEP_SECONDS + offset;
        return TotpAlgorithm.code(secret, counter);
    }

    private byte[] decodeBase32(String value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : value.toCharArray()) {
            int digit = alphabet.indexOf(character);
            if (digit < 0) throw new IllegalArgumentException("Invalid base32");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output.write((buffer >>> bits) & 0xff);
            }
        }
        return output.toByteArray();
    }

    private void assertRejected(ThrowingAction action) {
        assertThrows(OperatorAuthenticationException.class, action::run);
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

    private static String key(byte value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(filled(32, value));
    }

    private static byte[] filled(int length, byte value) {
        byte[] result = new byte[length];
        Arrays.fill(result, value);
        return result;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
