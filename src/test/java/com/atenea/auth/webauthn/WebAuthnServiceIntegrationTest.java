package com.atenea.auth.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.JwtTokenService;
import com.atenea.auth.MobileAuthSessionResponse;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.OperatorAuthenticationService;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.RefreshTokenService;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.action.PrivilegedActionFactor;
import com.atenea.auth.action.VerifiedStepUp;
import com.atenea.auth.session.SessionVersions;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialEntity;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.extension.ExtendWith;

@SpringBootTest(properties = {
        "atenea.auth.webauthn.enabled=true",
        "atenea.auth.webauthn.relying-party-id=atenea.yudri.es",
        "atenea.auth.webauthn.relying-party-name=Atenea",
        "atenea.auth.webauthn.web-origin=https://atenea.yudri.es",
        "atenea.auth.webauthn.android-origins[0]=android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "atenea.auth.webauthn.challenge-ttl=5m"
})
@ExtendWith(OutputCaptureExtension.class)
class WebAuthnServiceIntegrationTest {

    private static final String WEB_ORIGIN = "https://atenea.yudri.es";
    private static final String ANDROID_ORIGIN =
            "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Autowired private WebAuthnService webAuthnService;
    @Autowired private OperatorAuthenticationService authenticationService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private OperatorWebAuthnCredentialRepository credentialRepository;
    @Autowired private OperatorAuthProperties properties;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private OperatorEntity operator;
    private AuthenticatedOperator actor;
    private UUID familyId;
    private String preRegistrationAccessToken;

    @BeforeEach
    void setUp() {
        clearSyntheticState();
        properties.getWebAuthn().setEnabled(true);
        properties.getWebAuthn().setRelyingPartyId("atenea.yudri.es");
        properties.getWebAuthn().setWebOrigin(WEB_ORIGIN);
        properties.getWebAuthn().setAndroidOrigins(List.of(ANDROID_ORIGIN));
        properties.getWebAuthn().setChallengeTtl(java.time.Duration.ofMinutes(5));
        properties.getRecovery().setAttemptWindow(java.time.Duration.ofMinutes(5));
        properties.getRecovery().setLockout(java.time.Duration.ofMinutes(15));
        properties.getRecovery().setMaxAttempts(5);

        operator = new OperatorEntity();
        operator.setEmail("m121-" + UUID.randomUUID() + "@atenea.test");
        operator.setDisplayName("Synthetic M1.2.1 operator");
        operator.setPasswordHash(passwordEncoder.encode("synthetic-password"));
        operator.setActive(true);
        operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        operator.setCreatedAt(Instant.now());
        operator.setUpdatedAt(Instant.now());
        operator = operatorRepository.saveAndFlush(operator);
        actor = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
        RefreshTokenService.IssuedSession preRegistrationSession =
                refreshTokenService.createFamilySession(
                operator, "WEB", "Synthetic browser");
        familyId = preRegistrationSession.familyId();
        preRegistrationAccessToken = jwtTokenService.issueAccessToken(
                actor,
                familyId,
                new SessionVersions(
                        operator.getCredentialVersion(), operator.getRoleVersion()),
                preRegistrationSession.authenticatedAt(),
                preRegistrationSession.authenticationMethods()).token();
    }

    @AfterEach
    void tearDown() {
        properties.getWebAuthn().setEnabled(false);
        clearSyntheticState();
    }

    @Test
    void registersAndAuthenticatesWithExactClaimsAndDurablePublicMaterial()
            throws Exception {
        RegisteredPasskey passkey = register(0, false, false, WebAuthnChannel.WEB, WEB_ORIGIN);

        assertThrows(OperatorAuthenticationException.class,
                () -> authenticationService.authenticateAccessToken(
                        preRegistrationAccessToken));

        OperatorWebAuthnCredentialEntity stored = credentialRepository
                .findByCredentialId(passkey.credentialId()).orElseThrow();
        assertArrayEquals(passkey.credentialId(), stored.getCredentialId());
        assertTrue(stored.getPublicKeyCose().length >= 16);
        assertEquals(-7, stored.getAlgorithm());
        WebAuthnOptionsResponse sanitizedOptions = webAuthnService.beginRegistration(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN);
        assertEquals(List.of(-7, -8, -257), sanitizedOptions
                .credentialParameters().stream()
                .map(WebAuthnOptionsResponse.CredentialParameter::algorithm)
                .toList());
        assertTrue(sanitizedOptions.credentials().isEmpty());
        assertNotNull(stored.getAaguid());
        assertEquals(1L, credentialVersion());
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM operator_webauthn_challenge
                WHERE octet_length(challenge_digest) <> 32
                """, Integer.class));
        assertFalse(new String(passkey.userHandle(), StandardCharsets.UTF_8)
                .contains(operator.getEmail()));

        AuthenticatedOperator authenticated = authenticate(passkey, 1, true, true,
                WebAuthnChannel.WEB, WEB_ORIGIN, WEB_ORIGIN);
        assertEquals(operator.getId(), authenticated.operatorId());
        MobileAuthSessionResponse session = authenticationService.loginWithWebAuthn(
                authenticated, "FAMILY_V1", true, "WEB", "Passkey browser");
        JwtTokenService.ParsedAccessToken parsed = jwtTokenService.parseSessionAccessToken(
                session.accessToken());
        assertEquals(List.of("webauthn"), parsed.authenticationMethods());
        assertEquals(credentialVersion(), parsed.credentialVersion());
        assertNotNull(parsed.sessionFamilyId());
        assertEquals("webauthn", jdbcTemplate.queryForObject("""
                SELECT authentication_method FROM operator_session_family WHERE id = ?
                """, String.class, parsed.sessionFamilyId()));
    }

    @Test
    void rejectsAbsentExpiredConsumedPurposeAndCrossSessionChallenges() throws Exception {
        RegistrationMaterial material = registrationMaterial(
                fixedChallenge(), 0, false, false, WEB_ORIGIN);
        WebAuthnRegistrationRequest absent = material.request(UUID.randomUUID());
        assertRejected(() -> completeRegistration(absent, familyId));

        WebAuthnOptionsResponse expiredOptions = webAuthnService.beginRegistration(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN);
        RegistrationMaterial expiredMaterial = registrationMaterial(
                decode(expiredOptions.challenge()), 0, false, false, WEB_ORIGIN);
        jdbcTemplate.update("""
                UPDATE operator_webauthn_challenge
                SET created_at = now() - interval '10 seconds',
                    expires_at = now() - interval '1 second'
                WHERE id = ?
                """, expiredOptions.requestId());
        assertRejected(() -> completeRegistration(
                expiredMaterial.request(expiredOptions.requestId()), familyId));

        RegisteredPasskey passkey = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnOptionsResponse used = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest assertion = authenticationRequest(
                passkey, used, 1, true, true, WEB_ORIGIN);
        webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, assertion);
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, assertion));

        WebAuthnOptionsResponse authenticationChallenge = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        RegistrationMaterial wrongPurpose = registrationMaterial(
                decode(authenticationChallenge.challenge()), 0, false, false, WEB_ORIGIN);
        assertRejected(() -> completeRegistration(
                wrongPurpose.request(authenticationChallenge.requestId()), familyId));

        UUID otherFamily = refreshTokenService.createFamilySession(
                operator, "WEB", "Other synthetic browser").familyId();
        WebAuthnOptionsResponse bound = webAuthnService.beginRegistration(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN);
        RegistrationMaterial boundMaterial = registrationMaterial(
                decode(bound.challenge()), 0, false, false, WEB_ORIGIN);
        assertRejected(() -> completeRegistration(
                boundMaterial.request(bound.requestId()), otherFamily));
    }

    @Test
    void rejectsWrongOriginRpPresenceVerificationSignatureCredentialAndUserHandle()
            throws Exception {
        RegisteredPasskey passkey = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);

        assertRejected(() -> webAuthnService.beginAuthentication(
                WebAuthnChannel.WEB, "https://preview.atenea.yudri.es"));

        WebAuthnOptionsResponse wrongOrigin = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB,
                WEB_ORIGIN,
                authenticationRequest(passkey, wrongOrigin, 1, true, true,
                        "https://preview.atenea.yudri.es")));

        WebAuthnOptionsResponse wrongRp = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB,
                WEB_ORIGIN,
                authenticationRequest(passkey, wrongRp, 1, true, true,
                        WEB_ORIGIN, "other.example")));

        WebAuthnOptionsResponse noUp = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN,
                authenticationRequest(passkey, noUp, 1, false, true, WEB_ORIGIN)));

        WebAuthnOptionsResponse noUv = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN,
                authenticationRequest(passkey, noUv, 1, true, false, WEB_ORIGIN)));

        WebAuthnOptionsResponse badSignature = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest signatureRequest = authenticationRequest(
                passkey, badSignature, 1, true, true, WEB_ORIGIN);
        signatureRequest = new WebAuthnAuthenticationRequest(
                signatureRequest.requestId(), signatureRequest.credentialId(),
                signatureRequest.userHandle(), signatureRequest.clientDataJson(),
                signatureRequest.authenticatorData(), encode(new byte[64]),
                signatureRequest.sessionProtocolVersion(),
                signatureRequest.singleFlightRefresh(),
                signatureRequest.clientType(), signatureRequest.deviceLabel());
        WebAuthnAuthenticationRequest finalSignatureRequest = signatureRequest;
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, finalSignatureRequest));

        WebAuthnOptionsResponse wrongCredential = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest credentialRequest = authenticationRequest(
                passkey, wrongCredential, 1, true, true, WEB_ORIGIN);
        credentialRequest = new WebAuthnAuthenticationRequest(
                credentialRequest.requestId(), encode(randomBytes(32)),
                credentialRequest.userHandle(), credentialRequest.clientDataJson(),
                credentialRequest.authenticatorData(), credentialRequest.signature(),
                credentialRequest.sessionProtocolVersion(),
                credentialRequest.singleFlightRefresh(),
                credentialRequest.clientType(), credentialRequest.deviceLabel());
        WebAuthnAuthenticationRequest finalCredentialRequest = credentialRequest;
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, finalCredentialRequest));

        WebAuthnOptionsResponse wrongHandle = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest handleRequest = authenticationRequest(
                passkey, wrongHandle, 1, true, true, WEB_ORIGIN);
        handleRequest = new WebAuthnAuthenticationRequest(
                handleRequest.requestId(), handleRequest.credentialId(),
                encode(randomBytes(32)), handleRequest.clientDataJson(),
                handleRequest.authenticatorData(), handleRequest.signature(),
                handleRequest.sessionProtocolVersion(),
                handleRequest.singleFlightRefresh(),
                handleRequest.clientType(), handleRequest.deviceLabel());
        WebAuthnAuthenticationRequest finalHandleRequest = handleRequest;
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, finalHandleRequest));
    }

    @Test
    void permitsOnlyConfiguredAndroidOrigins() throws Exception {
        WebAuthnOptionsResponse options = webAuthnService.beginAuthentication(
                WebAuthnChannel.ANDROID, ANDROID_ORIGIN);
        assertEquals("atenea.yudri.es", options.relyingPartyId());
        assertRejected(() -> webAuthnService.beginAuthentication(
                WebAuthnChannel.ANDROID,
                "android:apk-key-hash:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));
        properties.getWebAuthn().setAndroidOrigins(List.of());
        assertRejected(() -> webAuthnService.beginAuthentication(
                WebAuthnChannel.ANDROID, ANDROID_ORIGIN));
        properties.getWebAuthn().setAndroidOrigins(List.of(
                ANDROID_ORIGIN, ANDROID_ORIGIN));
        assertRejected(() -> webAuthnService.beginAuthentication(
                WebAuthnChannel.ANDROID, ANDROID_ORIGIN));
    }

    @Test
    void counterPolicySupportsZeroAndSyncedPasskeysWithoutBlindlyAcceptingClones()
            throws Exception {
        RegisteredPasskey zero = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        authenticate(zero, 0, true, true,
                WebAuthnChannel.WEB, WEB_ORIGIN, WEB_ORIGIN);
        assertEquals(0, storedCounter(zero));

        RegisteredPasskey nonBackup = register(5, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        assertRejected(() -> authenticate(nonBackup, 4, true, true,
                WebAuthnChannel.WEB, WEB_ORIGIN, WEB_ORIGIN));
        assertEquals(5, storedCounter(nonBackup));

        RegisteredPasskey synced = register(5, true, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        authenticate(synced, 4, true, true,
                WebAuthnChannel.WEB, WEB_ORIGIN, WEB_ORIGIN);
        assertEquals(5, storedCounter(synced));
        authenticate(synced, 6, true, true, true,
                WebAuthnChannel.WEB, WEB_ORIGIN, WEB_ORIGIN);
        OperatorWebAuthnCredentialEntity stored = credentialRepository
                .findByCredentialId(synced.credentialId()).orElseThrow();
        assertEquals(6, stored.getSignCount());
        assertTrue(stored.isBackupState());
    }

    @Test
    void challengeAndCredentialLocksAllowExactlyOneConcurrentWinner() throws Exception {
        RegisteredPasskey passkey = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnOptionsResponse options = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest request = authenticationRequest(
                passkey, options, 1, true, true, WEB_ORIGIN);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try {
                webAuthnService.completeAuthentication(
                        WebAuthnChannel.WEB, WEB_ORIGIN, request);
                return true;
            } catch (OperatorAuthenticationException exception) {
                return false;
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            start.countDown();
            int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, winners);
        }
    }

    @Test
    void webAuthnStepUpIsBoundToActorSessionActionTargetAndPlan() throws Exception {
        RegisteredPasskey passkey = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        PrivilegedActionBinding binding = PrivilegedActionBinding.fromCanonical(
                "PUBLISH_RELEASE", "project=synthetic-a", "commit=abc;mode=ff");
        WebAuthnOptionsResponse options = webAuthnService.beginStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding);
        assertEquals(1, options.credentials().size());
        WebAuthnAuthenticationRequest request = authenticationRequest(
                passkey, options, 1, true, true, WEB_ORIGIN);
        VerifiedStepUp proof = webAuthnService.completeStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding, request);
        assertEquals(operator.getId(), proof.operatorId());
        assertEquals(familyId, proof.sessionFamilyId());
        assertEquals(PrivilegedActionFactor.WEBAUTHN, proof.factor());
        assertEquals(binding.actionKind(), proof.binding().actionKind());
        assertRejected(() -> webAuthnService.completeStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding, request));

        PrivilegedActionBinding otherTarget = PrivilegedActionBinding.fromCanonical(
                "PUBLISH_RELEASE", "project=synthetic-b", "commit=abc;mode=ff");
        WebAuthnOptionsResponse bound = webAuthnService.beginStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding);
        WebAuthnAuthenticationRequest boundRequest = authenticationRequest(
                passkey, bound, 2, true, true, WEB_ORIGIN);
        assertRejected(() -> webAuthnService.completeStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN,
                otherTarget, boundRequest));
        UUID otherFamily = refreshTokenService.createFamilySession(
                operator, "WEB", "Other step-up browser").familyId();
        assertRejected(() -> webAuthnService.completeStepUp(
                actor, otherFamily, WebAuthnChannel.WEB, WEB_ORIGIN,
                binding, boundRequest));
        assertEquals(PrivilegedActionFactor.WEBAUTHN,
                webAuthnService.completeStepUp(
                        actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN,
                        binding, boundRequest).factor());
    }

    @Test
    void webAuthnStepUpAttemptsAreDurablyRateLimitedAndRecoverAfterLockout()
            throws Exception {
        RegisteredPasskey passkey = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        PrivilegedActionBinding binding = PrivilegedActionBinding.fromCanonical(
                "PUBLISH_RELEASE", "project=rate-limit", "commit=abc;mode=ff");
        WebAuthnOptionsResponse options = webAuthnService.beginStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding);
        WebAuthnAuthenticationRequest valid = authenticationRequest(
                passkey, options, 1, true, true, WEB_ORIGIN);
        WebAuthnAuthenticationRequest invalid = new WebAuthnAuthenticationRequest(
                valid.requestId(), valid.credentialId(), valid.userHandle(),
                valid.clientDataJson(), valid.authenticatorData(), encode(new byte[64]),
                valid.sessionProtocolVersion(), valid.singleFlightRefresh(),
                valid.clientType(), valid.deviceLabel());
        properties.getRecovery().setMaxAttempts(2);
        assertRejected(() -> webAuthnService.completeStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding, invalid));
        assertRejected(() -> webAuthnService.completeStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding, invalid));
        assertRejected(() -> webAuthnService.beginStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding));
        assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT failed_count FROM operator_auth_attempt_window
                WHERE operator_id = ? AND scope = 'STEP_UP'
                """, Integer.class, operator.getId()));
        jdbcTemplate.update("""
                UPDATE operator_auth_attempt_window
                SET window_started_at = now() - interval '20 minutes',
                    blocked_until = now() - interval '1 minute', updated_at = now()
                WHERE operator_id = ? AND scope = 'STEP_UP'
                """, operator.getId());
        assertNotNull(webAuthnService.beginStepUp(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN, binding));
    }

    @Test
    void duplicateAndCrossOperatorCredentialsFailClosed() throws Exception {
        RegisteredPasskey passkey = register(0, false, false,
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnOptionsResponse duplicate = webAuthnService.beginRegistration(
                actor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN);
        RegistrationMaterial duplicateMaterial = registrationMaterial(
                decode(duplicate.challenge()), passkey.keyPair(), passkey.credentialId(),
                0, false, false, WEB_ORIGIN, "atenea.yudri.es");
        assertRejected(() -> completeRegistration(
                duplicateMaterial.request(duplicate.requestId()), familyId));

        OperatorEntity foreign = new OperatorEntity();
        foreign.setEmail("foreign-" + UUID.randomUUID() + "@atenea.test");
        foreign.setDisplayName("Foreign synthetic operator");
        foreign.setPasswordHash(passwordEncoder.encode("synthetic-password"));
        foreign.setActive(true);
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        foreign = operatorRepository.saveAndFlush(foreign);
        AuthenticatedOperator foreignActor = new AuthenticatedOperator(
                foreign.getId(), foreign.getEmail(), foreign.getDisplayName());
        UUID foreignFamily = refreshTokenService.createFamilySession(
                foreign, "WEB", "Foreign browser").familyId();
        assertRejected(() -> webAuthnService.beginRegistration(
                foreignActor, familyId, WebAuthnChannel.WEB, WEB_ORIGIN));

        WebAuthnOptionsResponse authOptions = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest request = authenticationRequest(
                passkey, authOptions, 1, true, true, WEB_ORIGIN);
        byte[] foreignHandle = randomBytes(32);
        jdbcTemplate.update("""
                INSERT INTO operator_webauthn_user (
                    operator_id, user_handle, created_at, updated_at)
                VALUES (?, ?, now(), now())
                """, foreign.getId(), foreignHandle);
        WebAuthnAuthenticationRequest crossOwner = new WebAuthnAuthenticationRequest(
                request.requestId(), request.credentialId(), encode(foreignHandle),
                request.clientDataJson(), request.authenticatorData(), request.signature(),
                request.sessionProtocolVersion(), request.singleFlightRefresh(),
                request.clientType(), request.deviceLabel());
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, crossOwner));

        OperatorWebAuthnCredentialEntity revoked = credentialRepository
                .findByCredentialId(passkey.credentialId()).orElseThrow();
        revoked.setRevokedAt(Instant.now());
        revoked.setRevocationReason("SYNTHETIC_TEST");
        credentialRepository.saveAndFlush(revoked);
        WebAuthnOptionsResponse revokedOptions = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        WebAuthnAuthenticationRequest revokedRequest = authenticationRequest(
                passkey, revokedOptions, 1, true, true, WEB_ORIGIN);
        assertRejected(() -> webAuthnService.completeAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN, revokedRequest));
        assertNotNull(foreignFamily);
    }

    @Test
    void disabledFeaturePreservesLegacyLoginAndSensitiveValuesStayOutOfMessagesAndLogs(
            CapturedOutput output
    ) throws Exception {
        properties.getWebAuthn().setEnabled(false);
        assertRejected(() -> webAuthnService.beginAuthentication(
                WebAuthnChannel.WEB, WEB_ORIGIN));
        MobileAuthSessionResponse legacy = authenticationService.login(
                new com.atenea.auth.MobileLoginRequest(
                        operator.getEmail(), "synthetic-password",
                        null, null, null, null));
        assertNotNull(legacy.accessToken());

        properties.getWebAuthn().setEnabled(true);
        WebAuthnOptionsResponse options = authenticationOptions(
                WebAuthnChannel.WEB, WEB_ORIGIN);
        String marker = options.challenge();
        WebAuthnAuthenticationRequest request = new WebAuthnAuthenticationRequest(
                options.requestId(), marker, marker, marker, marker, marker,
                "FAMILY_V1", true, "WEB", "Synthetic browser");
        OperatorAuthenticationException exception = assertThrows(
                OperatorAuthenticationException.class,
                () -> webAuthnService.completeAuthentication(
                        WebAuthnChannel.WEB, WEB_ORIGIN, request));
        assertEquals("WebAuthn ceremony rejected", exception.getMessage());
        assertFalse(request.toString().contains(marker));
        assertFalse(options.toString().contains(marker));
        assertFalse(output.getAll().contains(marker));
    }

    private RegisteredPasskey register(
            long counter,
            boolean backupEligible,
            boolean backupState,
            WebAuthnChannel channel,
            String origin
    ) throws Exception {
        WebAuthnOptionsResponse options = webAuthnService.beginRegistration(
                actor, familyId, channel, origin);
        RegistrationMaterial material = registrationMaterial(
                decode(options.challenge()), counter, backupEligible, backupState, origin);
        completeRegistration(material.request(options.requestId()), familyId, channel, origin);
        return new RegisteredPasskey(
                material.keyPair(), material.credentialId(), decode(options.userHandle()),
                backupEligible);
    }

    private void completeRegistration(WebAuthnRegistrationRequest request, UUID sessionId) {
        completeRegistration(request, sessionId, WebAuthnChannel.WEB, WEB_ORIGIN);
    }

    private void completeRegistration(
            WebAuthnRegistrationRequest request,
            UUID sessionId,
            WebAuthnChannel channel,
            String origin
    ) {
        webAuthnService.completeRegistration(actor, sessionId, channel, origin, request);
    }

    private AuthenticatedOperator authenticate(
            RegisteredPasskey passkey,
            long counter,
            boolean up,
            boolean uv,
            WebAuthnChannel channel,
            String routeOrigin,
            String clientOrigin
    ) throws Exception {
        return authenticate(passkey, counter, up, uv, false,
                channel, routeOrigin, clientOrigin);
    }

    private AuthenticatedOperator authenticate(
            RegisteredPasskey passkey,
            long counter,
            boolean up,
            boolean uv,
            boolean backupState,
            WebAuthnChannel channel,
            String routeOrigin,
            String clientOrigin
    ) throws Exception {
        WebAuthnOptionsResponse options = authenticationOptions(channel, routeOrigin);
        return webAuthnService.completeAuthentication(
                channel,
                routeOrigin,
                authenticationRequest(passkey, options, counter, up, uv,
                        backupState, clientOrigin, "atenea.yudri.es"));
    }

    private WebAuthnOptionsResponse authenticationOptions(
            WebAuthnChannel channel,
            String origin
    ) {
        return webAuthnService.beginAuthentication(channel, origin);
    }

    private WebAuthnAuthenticationRequest authenticationRequest(
            RegisteredPasskey passkey,
            WebAuthnOptionsResponse options,
            long counter,
            boolean up,
            boolean uv,
            String origin
    ) throws Exception {
        return authenticationRequest(passkey, options, counter, up, uv,
                false, origin, "atenea.yudri.es");
    }

    private WebAuthnAuthenticationRequest authenticationRequest(
            RegisteredPasskey passkey,
            WebAuthnOptionsResponse options,
            long counter,
            boolean up,
            boolean uv,
            String origin,
            String rpId
    ) throws Exception {
        return authenticationRequest(passkey, options, counter, up, uv,
                false, origin, rpId);
    }

    private WebAuthnAuthenticationRequest authenticationRequest(
            RegisteredPasskey passkey,
            WebAuthnOptionsResponse options,
            long counter,
            boolean up,
            boolean uv,
            boolean backupState,
            String origin,
            String rpId
    ) throws Exception {
        byte[] clientData = clientData(
                "webauthn.get", decode(options.challenge()), origin);
        byte flags = flags(up, uv, passkey.backupEligible(), backupState, false);
        AuthenticatorData<?> authenticator = new AuthenticatorData<>(
                sha256(rpId.getBytes(StandardCharsets.UTF_8)), flags, counter);
        byte[] authenticatorBytes = new AuthenticatorDataConverter(new ObjectConverter())
                .convert(authenticator);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(passkey.keyPair().getPrivate());
        signer.update(authenticatorBytes);
        signer.update(sha256(clientData));
        byte[] signature = signer.sign();
        return new WebAuthnAuthenticationRequest(
                options.requestId(),
                encode(passkey.credentialId()),
                encode(passkey.userHandle()),
                encode(clientData),
                encode(authenticatorBytes),
                encode(signature),
                "FAMILY_V1",
                true,
                "WEB",
                "Synthetic passkey browser");
    }

    private RegistrationMaterial registrationMaterial(
            byte[] challenge,
            long counter,
            boolean backupEligible,
            boolean backupState,
            String origin
    ) throws Exception {
        return registrationMaterial(
                challenge, keyPair(), randomBytes(32), counter,
                backupEligible, backupState, origin, "atenea.yudri.es");
    }

    private RegistrationMaterial registrationMaterial(
            byte[] challenge,
            KeyPair keyPair,
            byte[] credentialId,
            long counter,
            boolean backupEligible,
            boolean backupState,
            String origin,
            String rpId
    ) throws Exception {
        EC2COSEKey coseKey = EC2COSEKey.create(
                (ECPublicKey) keyPair.getPublic(), COSEAlgorithmIdentifier.ES256);
        AttestedCredentialData attested = new AttestedCredentialData(
                AAGUID.ZERO, credentialId, coseKey);
        byte flags = flags(true, true, backupEligible, backupState, true);
        AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authenticator =
                new AuthenticatorData<>(
                        sha256(rpId.getBytes(StandardCharsets.UTF_8)),
                        flags,
                        counter,
                        attested);
        AttestationObject attestation = new AttestationObject(
                authenticator, new NoneAttestationStatement());
        byte[] attestationBytes = new AttestationObjectConverter(new ObjectConverter())
                .convertToBytes(attestation);
        return new RegistrationMaterial(
                keyPair,
                credentialId,
                encode(clientData("webauthn.create", challenge, origin)),
                encode(attestationBytes));
    }

    private byte[] clientData(String type, byte[] challenge, String origin) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\""
                + encode(challenge) + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private byte flags(
            boolean up,
            boolean uv,
            boolean backupEligible,
            boolean backupState,
            boolean attested
    ) {
        int flags = 0;
        if (up) flags |= AuthenticatorData.BIT_UP;
        if (uv) flags |= AuthenticatorData.BIT_UV;
        if (backupEligible) flags |= AuthenticatorData.BIT_BE;
        if (backupState) flags |= AuthenticatorData.BIT_BS;
        if (attested) flags |= AuthenticatorData.BIT_AT;
        return (byte) flags;
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return generator.generateKeyPair();
    }

    private long credentialVersion() {
        return jdbcTemplate.queryForObject("""
                SELECT credential_version FROM operator_account WHERE id = ?
                """, Long.class, operator.getId());
    }

    private long storedCounter(RegisteredPasskey passkey) {
        return credentialRepository.findByCredentialId(passkey.credentialId())
                .orElseThrow().getSignCount();
    }

    private void assertRejected(ThrowingAction action) {
        assertThrows(OperatorAuthenticationException.class, action::run);
    }

    private void clearSyntheticState() {
        jdbcTemplate.update("DELETE FROM operator_privileged_action_authorization");
        jdbcTemplate.update("DELETE FROM operator_security_notification");
        jdbcTemplate.update("DELETE FROM operator_security_event");
        jdbcTemplate.update("DELETE FROM operator_auth_attempt_window");
        jdbcTemplate.update("DELETE FROM operator_webauthn_challenge");
        jdbcTemplate.update("DELETE FROM operator_webauthn_credential");
        jdbcTemplate.update("DELETE FROM operator_webauthn_user");
        jdbcTemplate.update("DELETE FROM operator_refresh_token");
        jdbcTemplate.update("DELETE FROM operator_session_family");
        jdbcTemplate.update("DELETE FROM operator_account WHERE email LIKE '%@atenea.test'");
    }

    private byte[] fixedChallenge() {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, (byte) 7);
        return value;
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private record RegistrationMaterial(
            KeyPair keyPair,
            byte[] credentialId,
            String clientDataJson,
            String attestationObject
    ) {
        private WebAuthnRegistrationRequest request(UUID requestId) {
            return new WebAuthnRegistrationRequest(
                    requestId,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId),
                    clientDataJson,
                    attestationObject,
                    Set.of("internal", "hybrid"));
        }
    }

    private record RegisteredPasskey(
            KeyPair keyPair,
            byte[] credentialId,
            byte[] userHandle,
            boolean backupEligible
    ) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
