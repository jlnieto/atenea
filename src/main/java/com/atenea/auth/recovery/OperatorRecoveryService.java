package com.atenea.auth.recovery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.RefreshTokenService;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.action.PrivilegedActionFactor;
import com.atenea.auth.action.VerifiedStepUp;
import com.atenea.persistence.auth.OperatorAuthAttemptEntity;
import com.atenea.persistence.auth.OperatorAuthAttemptRepository;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRecoveryCodeEntity;
import com.atenea.persistence.auth.OperatorRecoveryCodeRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorSecurityEventEntity;
import com.atenea.persistence.auth.OperatorSecurityEventRepository;
import com.atenea.persistence.auth.OperatorSecurityNotificationEntity;
import com.atenea.persistence.auth.OperatorSecurityNotificationRepository;
import com.atenea.persistence.auth.OperatorSessionFamilyEntity;
import com.atenea.persistence.auth.OperatorSessionFamilyRepository;
import com.atenea.persistence.auth.OperatorTotpFactorEntity;
import com.atenea.persistence.auth.OperatorTotpFactorRepository;
import com.atenea.persistence.auth.OperatorWebAuthnChallengeRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorRecoveryService {
    private static final int TOTP_SECRET_BYTES = 20;
    private static final int RECOVERY_CODE_BYTES = 16;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int AES_GCM_NONCE_BYTES = 12;
    private static final int AES_GCM_TAG_BITS = 128;
    private static final Pattern KEY_VERSION = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final Pattern RECOVERY_CODE = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final OperatorRepository operatorRepository;
    private final OperatorTotpFactorRepository factorRepository;
    private final OperatorRecoveryCodeRepository recoveryCodeRepository;
    private final OperatorAuthAttemptRepository attemptRepository;
    private final OperatorSecurityEventRepository eventRepository;
    private final OperatorSecurityNotificationRepository notificationRepository;
    private final OperatorWebAuthnCredentialRepository webAuthnCredentialRepository;
    private final OperatorWebAuthnChallengeRepository webAuthnChallengeRepository;
    private final OperatorSessionFamilyRepository sessionFamilyRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final OperatorAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public OperatorRecoveryService(
            OperatorRepository operatorRepository,
            OperatorTotpFactorRepository factorRepository,
            OperatorRecoveryCodeRepository recoveryCodeRepository,
            OperatorAuthAttemptRepository attemptRepository,
            OperatorSecurityEventRepository eventRepository,
            OperatorSecurityNotificationRepository notificationRepository,
            OperatorWebAuthnCredentialRepository webAuthnCredentialRepository,
            OperatorWebAuthnChallengeRepository webAuthnChallengeRepository,
            OperatorSessionFamilyRepository sessionFamilyRepository,
            RefreshTokenService refreshTokenService,
            PasswordEncoder passwordEncoder,
            OperatorAuthProperties properties
    ) {
        this.operatorRepository = operatorRepository;
        this.factorRepository = factorRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.notificationRepository = notificationRepository;
        this.webAuthnCredentialRepository = webAuthnCredentialRepository;
        this.webAuthnChallengeRepository = webAuthnChallengeRepository;
        this.sessionFamilyRepository = sessionFamilyRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Transactional
    public TotpEnrollmentStartResponse beginTotpEnrollment(AuthenticatedOperator actor) {
        SecurityConfiguration configuration = configuration(true, false);
        Instant now = Instant.now();
        OperatorEntity operator = activeOperatorForUpdate(actor.operatorId());
        terminalizeFactors(
                factorRepository.findAllByOperatorIdAndStateForUpdate(
                        operator.getId(), TotpFactorState.PENDING),
                TotpFactorState.CANCELLED,
                "SUPERSEDED",
                now);

        byte[] secret = randomBytes(TOTP_SECRET_BYTES);
        try {
            UUID enrollmentId = UUID.randomUUID();
            OperatorTotpFactorEntity factor = new OperatorTotpFactorEntity();
            factor.setId(UUID.randomUUID());
            factor.setOperator(operator);
            factor.setEnrollmentId(enrollmentId);
            factor.setEncryptedSecret(encrypt(
                    secret,
                    configuration.activeEncryptionKey(),
                    operator.getId(),
                    enrollmentId));
            factor.setSecretKeyVersion(configuration.activeEncryptionVersion());
            factor.setState(TotpFactorState.PENDING);
            factor.setCreatedAt(now);
            factor.setExpiresAt(now.plus(validEnrollmentTtl()));
            factorRepository.saveAndFlush(factor);
            event(operator, "TOTP_ENROLLMENT_STARTED", "SUCCEEDED", now);
            return new TotpEnrollmentStartResponse(
                    enrollmentId,
                    base32(secret),
                    "SHA1",
                    TotpAlgorithm.DIGITS,
                    Math.toIntExact(TotpAlgorithm.STEP_SECONDS),
                    factor.getExpiresAt());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Transactional(noRollbackFor = OperatorAuthenticationException.class)
    public TotpEnrollmentActivationResponse activateTotpEnrollment(
            AuthenticatedOperator actor,
            TotpEnrollmentActivationRequest request
    ) {
        SecurityConfiguration configuration = configuration(true, true);
        Instant now = Instant.now();
        OperatorEntity operator = activeOperatorForUpdate(actor.operatorId());
        assertAttemptAllowed(operator, AuthAttemptScope.TOTP_ENROLLMENT,
                "TOTP_ENROLLMENT_RATE_LIMITED", now);
        OperatorTotpFactorEntity factor = factorRepository
                .findByEnrollmentIdForUpdate(request.enrollmentId())
                .filter(candidate -> candidate.getOperator().getId().equals(operator.getId()))
                .orElseGet(() -> reject(operator, AuthAttemptScope.TOTP_ENROLLMENT,
                        "TOTP_ENROLLMENT_REJECTED", now));
        if (factor.getState() != TotpFactorState.PENDING) {
            return reject(operator, AuthAttemptScope.TOTP_ENROLLMENT,
                    "TOTP_ENROLLMENT_REJECTED", now);
        }
        if (!factor.getExpiresAt().isAfter(now)) {
            terminalizeFactor(factor, TotpFactorState.EXPIRED, "ENROLLMENT_EXPIRED", now);
            factorRepository.saveAndFlush(factor);
            return reject(operator, AuthAttemptScope.TOTP_ENROLLMENT,
                    "TOTP_ENROLLMENT_REJECTED", now);
        }

        byte[] secret;
        try {
            secret = decryptFactor(factor, configuration);
        } catch (OperatorAuthenticationException exception) {
            return reject(operator, AuthAttemptScope.TOTP_ENROLLMENT,
                    "TOTP_ENROLLMENT_REJECTED", now);
        }
        try {
            OptionalLong matched = TotpAlgorithm.matchingCounter(secret, request.code(), now);
            if (matched.isEmpty()) {
                return reject(operator, AuthAttemptScope.TOTP_ENROLLMENT,
                        "TOTP_ENROLLMENT_REJECTED", now);
            }
            terminalizeFactors(
                    factorRepository.findAllByOperatorIdAndStateForUpdate(
                            operator.getId(), TotpFactorState.ACTIVE),
                    TotpFactorState.REVOKED,
                    "REPLACED",
                    now);
            revokeRecoveryCodes(operator.getId(), "REPLACED", now, null);
            factor.setState(TotpFactorState.ACTIVE);
            factor.setActivatedAt(now);
            factor.setLastAcceptedCounter(matched.getAsLong());
            factorRepository.saveAndFlush(factor);
            List<String> codes = createRecoveryCodes(
                    operator, factor, configuration, now);
            advanceCredentialVersion(operator, now);
            operator.setFactorReenrollmentRequired(false);
            operatorRepository.saveAndFlush(operator);
            clearAttempts(operator.getId(), AuthAttemptScope.TOTP_ENROLLMENT);
            event(operator, "TOTP_ENROLLMENT_ACTIVATED", "SUCCEEDED", now);
            return new TotpEnrollmentActivationResponse(codes);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Transactional
    public void cancelTotpEnrollment(AuthenticatedOperator actor, UUID enrollmentId) {
        configuration(true, false);
        Instant now = Instant.now();
        OperatorEntity operator = activeOperatorForUpdate(actor.operatorId());
        OperatorTotpFactorEntity factor = factorRepository
                .findByEnrollmentIdForUpdate(enrollmentId)
                .filter(candidate -> candidate.getOperator().getId().equals(operator.getId()))
                .filter(candidate -> candidate.getState() == TotpFactorState.PENDING)
                .orElseThrow(OperatorRecoveryService::factorRejected);
        terminalizeFactor(factor, TotpFactorState.CANCELLED, "OPERATOR_CANCELLED", now);
        factorRepository.saveAndFlush(factor);
        event(operator, "TOTP_ENROLLMENT_CANCELLED", "SUCCEEDED", now);
    }

    @Transactional(noRollbackFor = OperatorAuthenticationException.class)
    public void removeTotpFactor(
            AuthenticatedOperator actor,
            TotpFactorRemovalRequest request
    ) {
        SecurityConfiguration configuration = configuration(true, false);
        Instant now = Instant.now();
        OperatorEntity operator = activeOperatorForUpdate(actor.operatorId());
        assertAttemptAllowed(operator, AuthAttemptScope.TOTP_REMOVAL,
                "TOTP_REMOVAL_RATE_LIMITED", now);
        List<OperatorTotpFactorEntity> active = factorRepository
                .findAllByOperatorIdAndStateForUpdate(operator.getId(), TotpFactorState.ACTIVE);
        if (active.size() != 1) {
            reject(operator, AuthAttemptScope.TOTP_REMOVAL, "TOTP_REMOVAL_REJECTED", now);
        }
        OperatorTotpFactorEntity factor = active.get(0);
        byte[] secret;
        try {
            secret = decryptFactor(factor, configuration);
        } catch (OperatorAuthenticationException exception) {
            reject(operator, AuthAttemptScope.TOTP_REMOVAL, "TOTP_REMOVAL_REJECTED", now);
            return;
        }
        try {
            OptionalLong matched = TotpAlgorithm.matchingCounter(secret, request.code(), now);
            if (matched.isEmpty()
                    || (factor.getLastAcceptedCounter() != null
                        && matched.getAsLong() <= factor.getLastAcceptedCounter())) {
                reject(operator, AuthAttemptScope.TOTP_REMOVAL, "TOTP_REMOVAL_REJECTED", now);
            }
            factor.setLastAcceptedCounter(matched.getAsLong());
            terminalizeFactor(factor, TotpFactorState.REVOKED, "OPERATOR_REMOVED", now);
            factorRepository.saveAndFlush(factor);
            revokeRecoveryCodes(operator.getId(), "FACTOR_REMOVED", now, null);
            advanceCredentialVersion(operator, now);
            operator.setFactorReenrollmentRequired(true);
            operatorRepository.saveAndFlush(operator);
            clearAttempts(operator.getId(), AuthAttemptScope.TOTP_REMOVAL);
            event(operator, "TOTP_FACTOR_REMOVED", "SUCCEEDED", now);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Transactional(noRollbackFor = OperatorAuthenticationException.class)
    public void recover(AccountRecoveryRequest request) {
        SecurityConfiguration configuration = configuration(false, true);
        Instant now = Instant.now();
        String email = request.email() == null ? "" : request.email().trim();
        Optional<OperatorEntity> found = operatorRepository.findByEmailIgnoreCase(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            hmacCandidates(canonicalRecoveryCodeOrDummy(request.recoveryCode()), configuration);
            event(null, "ACCOUNT_RECOVERY_REJECTED", "REJECTED", now);
            throw recoveryRejected();
        }
        OperatorEntity operator = operatorRepository
                .findByIdForUpdate(found.get().getId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(OperatorRecoveryService::recoveryRejected);
        assertAttemptAllowed(operator, AuthAttemptScope.RECOVERY,
                "ACCOUNT_RECOVERY_RATE_LIMITED", now);

        boolean passwordMatches = passwordEncoder.matches(
                request.password(), operator.getPasswordHash());
        boolean canonical = request.recoveryCode() != null
                && RECOVERY_CODE.matcher(request.recoveryCode()).matches();
        String hmacInput = canonicalRecoveryCodeOrDummy(request.recoveryCode());
        List<HmacCandidate> candidates = hmacCandidates(hmacInput, configuration);
        List<OperatorRecoveryCodeEntity> matches = new ArrayList<>();
        for (HmacCandidate candidate : candidates) {
            recoveryCodeRepository.findByCodeHmacForUpdate(candidate.digest())
                    .ifPresent(matches::add);
        }
        if (!passwordMatches || !canonical || matches.size() != 1) {
            reject(operator, AuthAttemptScope.RECOVERY, "ACCOUNT_RECOVERY_REJECTED", now);
        }
        OperatorRecoveryCodeEntity code = matches.get(0);
        byte[] configuredKey = configuration.hmacKeys().get(code.getHmacKeyVersion());
        byte[] expected = configuredKey == null
                ? new byte[32]
                : hmac(request.recoveryCode(), configuredKey);
        boolean exact = configuredKey != null
                && MessageDigest.isEqual(expected, code.getCodeHmac());
        Arrays.fill(expected, (byte) 0);
        if (!exact
                || !code.getOperator().getId().equals(operator.getId())
                || code.getConsumedAt() != null
                || code.getRevokedAt() != null) {
            reject(operator, AuthAttemptScope.RECOVERY, "ACCOUNT_RECOVERY_REJECTED", now);
        }

        code.setConsumedAt(now);
        recoveryCodeRepository.saveAndFlush(code);
        revokeRecoveryCodes(operator.getId(), "ACCOUNT_RECOVERED", now, code.getId());
        terminalizeFactors(
                factorRepository.findAllByOperatorIdAndStateForUpdate(
                        operator.getId(), TotpFactorState.ACTIVE),
                TotpFactorState.REVOKED,
                "ACCOUNT_RECOVERED",
                now);
        terminalizeFactors(
                factorRepository.findAllByOperatorIdAndStateForUpdate(
                        operator.getId(), TotpFactorState.PENDING),
                TotpFactorState.REVOKED,
                "ACCOUNT_RECOVERED",
                now);
        webAuthnCredentialRepository.revokeActiveByOperatorId(
                operator.getId(), now, "ACCOUNT_RECOVERED");
        webAuthnChallengeRepository.consumeActiveByOperatorId(operator.getId(), now);
        refreshTokenService.revokeAllSessions(operator.getId());
        operator.setFactorReenrollmentRequired(true);
        operator.setUpdatedAt(now);
        operatorRepository.saveAndFlush(operator);
        clearAttempts(operator.getId(), AuthAttemptScope.RECOVERY);
        OperatorSecurityEventEntity securityEvent = event(
                operator, "ACCOUNT_RECOVERED", "SUCCEEDED", now);
        notification(operator, securityEvent, now);
    }

    public boolean enforcementEnabled() {
        return properties.getRecovery().isEnforcementEnabled();
    }

    @Transactional(noRollbackFor = OperatorAuthenticationException.class)
    public VerifiedStepUp verifyTotpStepUp(
            AuthenticatedOperator actor,
            UUID sessionFamilyId,
            PrivilegedActionBinding binding,
            String code
    ) {
        SecurityConfiguration configuration = configuration(true, false);
        Instant now = Instant.now();
        OperatorEntity operator = activeOperatorForUpdate(actor.operatorId());
        assertAttemptAllowed(operator, AuthAttemptScope.STEP_UP,
                "STEP_UP_RATE_LIMITED", now);
        OperatorSessionFamilyEntity family = sessionFamilyRepository
                .findByIdForUpdate(sessionFamilyId)
                .filter(candidate -> candidate.getOperator().getId().equals(operator.getId()))
                .filter(candidate -> candidate.getRevokedAt() == null)
                .filter(candidate -> candidate.getAbsoluteExpiresAt().isAfter(now))
                .orElse(null);
        List<OperatorTotpFactorEntity> active = factorRepository
                .findAllByOperatorIdAndStateForUpdate(operator.getId(), TotpFactorState.ACTIVE);
        if (family == null || binding == null || active.size() != 1
                || operator.isFactorReenrollmentRequired()) {
            return reject(operator, AuthAttemptScope.STEP_UP, "STEP_UP_REJECTED", now);
        }
        OperatorTotpFactorEntity factor = active.get(0);
        byte[] secret;
        try {
            secret = decryptFactor(factor, configuration);
        } catch (OperatorAuthenticationException exception) {
            return reject(operator, AuthAttemptScope.STEP_UP, "STEP_UP_REJECTED", now);
        }
        try {
            OptionalLong matched = TotpAlgorithm.matchingCounter(secret, code, now);
            if (matched.isEmpty() || (factor.getLastAcceptedCounter() != null
                    && matched.getAsLong() <= factor.getLastAcceptedCounter())) {
                return reject(operator, AuthAttemptScope.STEP_UP, "STEP_UP_REJECTED", now);
            }
            factor.setLastAcceptedCounter(matched.getAsLong());
            factorRepository.saveAndFlush(factor);
            clearAttempts(operator.getId(), AuthAttemptScope.STEP_UP);
            event(operator, "STEP_UP_VERIFIED", "SUCCEEDED", now);
            return new VerifiedStepUp(operator.getId(), family.getId(), binding,
                    PrivilegedActionFactor.TOTP, now);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private SecurityConfiguration configuration(boolean requireTotp, boolean requireRecovery) {
        OperatorAuthProperties.Recovery configured = properties.getRecovery();
        if ((requireTotp && !configured.isTotpEnabled())
                || (requireRecovery && !configured.isRecoveryEnabled())) {
            throw factorRejected();
        }
        KeyConfiguration encryption = parseKeys(
                configured.getEncryptionKeys(), configured.getActiveEncryptionKeyVersion());
        KeyConfiguration hmac = parseKeys(
                configured.getHmacKeys(), configured.getActiveHmacKeyVersion());
        validRateConfiguration();
        validEnrollmentTtl();
        return new SecurityConfiguration(
                encryption.keys(), encryption.activeVersion(),
                hmac.keys(), hmac.activeVersion());
    }

    private KeyConfiguration parseKeys(List<String> entries, String activeVersion) {
        if (entries == null || entries.isEmpty()
                || activeVersion == null || !KEY_VERSION.matcher(activeVersion).matches()) {
            throw factorRejected();
        }
        Map<String, byte[]> keys = new HashMap<>();
        Set<String> material = new HashSet<>();
        for (String entry : entries) {
            if (entry == null || !entry.equals(entry.trim())) {
                throw factorRejected();
            }
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator != entry.lastIndexOf(':')) {
                throw factorRejected();
            }
            String version = entry.substring(0, separator);
            String encoded = entry.substring(separator + 1);
            if (!KEY_VERSION.matcher(version).matches() || encoded.isEmpty()
                    || encoded.indexOf('=') >= 0) {
                throw factorRejected();
            }
            byte[] key;
            try {
                key = Base64.getUrlDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw factorRejected();
            }
            if (key.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(encoded)
                    || keys.putIfAbsent(version, key) != null
                    || !material.add(encoded)) {
                throw factorRejected();
            }
        }
        if (!keys.containsKey(activeVersion)) {
            throw factorRejected();
        }
        return new KeyConfiguration(Map.copyOf(keys), activeVersion);
    }

    private Duration validEnrollmentTtl() {
        Duration value = properties.getRecovery().getEnrollmentTtl();
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofMinutes(30)) > 0) {
            throw factorRejected();
        }
        return value;
    }

    private void validRateConfiguration() {
        OperatorAuthProperties.Recovery value = properties.getRecovery();
        if (value.getMaxAttempts() < 1 || value.getMaxAttempts() > 20
                || invalidDuration(value.getAttemptWindow(), Duration.ofHours(1))
                || invalidDuration(value.getLockout(), Duration.ofDays(1))) {
            throw factorRejected();
        }
    }

    private boolean invalidDuration(Duration value, Duration maximum) {
        return value == null || value.isZero() || value.isNegative()
                || value.compareTo(maximum) > 0;
    }

    private OperatorEntity activeOperatorForUpdate(Long operatorId) {
        return operatorRepository.findByIdForUpdate(operatorId)
                .filter(OperatorEntity::isActive)
                .orElseThrow(OperatorRecoveryService::factorRejected);
    }

    private byte[] encrypt(
            byte[] secret,
            byte[] key,
            Long operatorId,
            UUID enrollmentId
    ) {
        try {
            byte[] nonce = randomBytes(AES_GCM_NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(AES_GCM_TAG_BITS, nonce));
            cipher.updateAAD(factorBinding(operatorId, enrollmentId));
            byte[] ciphertext = cipher.doFinal(secret);
            byte[] result = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
            return result;
        } catch (Exception exception) {
            throw factorRejected();
        }
    }

    private byte[] decryptFactor(
            OperatorTotpFactorEntity factor,
            SecurityConfiguration configuration
    ) {
        byte[] key = configuration.encryptionKeys().get(factor.getSecretKeyVersion());
        byte[] encrypted = factor.getEncryptedSecret();
        if (key == null || encrypted == null || encrypted.length < AES_GCM_NONCE_BYTES + 17) {
            throw factorRejected();
        }
        try {
            byte[] nonce = Arrays.copyOfRange(encrypted, 0, AES_GCM_NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(AES_GCM_TAG_BITS, nonce));
            cipher.updateAAD(factorBinding(
                    factor.getOperator().getId(), factor.getEnrollmentId()));
            byte[] secret = cipher.doFinal(
                    Arrays.copyOfRange(encrypted, AES_GCM_NONCE_BYTES, encrypted.length));
            if (secret.length != TOTP_SECRET_BYTES) {
                Arrays.fill(secret, (byte) 0);
                throw factorRejected();
            }
            return secret;
        } catch (OperatorAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw factorRejected();
        }
    }

    private List<String> createRecoveryCodes(
            OperatorEntity operator,
            OperatorTotpFactorEntity factor,
            SecurityConfiguration configuration,
            Instant now
    ) {
        List<String> rawCodes = new ArrayList<>();
        List<OperatorRecoveryCodeEntity> entities = new ArrayList<>();
        UUID batchId = UUID.randomUUID();
        for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
            String raw = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(randomBytes(RECOVERY_CODE_BYTES));
            OperatorRecoveryCodeEntity entity = new OperatorRecoveryCodeEntity();
            entity.setId(UUID.randomUUID());
            entity.setOperator(operator);
            entity.setFactor(factor);
            entity.setBatchId(batchId);
            entity.setCodeHmac(hmac(raw, configuration.activeHmacKey()));
            entity.setHmacKeyVersion(configuration.activeHmacVersion());
            entity.setCreatedAt(now);
            rawCodes.add(raw);
            entities.add(entity);
        }
        if (new HashSet<>(rawCodes).size() != RECOVERY_CODE_COUNT) {
            throw factorRejected();
        }
        recoveryCodeRepository.saveAllAndFlush(entities);
        return List.copyOf(rawCodes);
    }

    private List<HmacCandidate> hmacCandidates(
            String raw,
            SecurityConfiguration configuration
    ) {
        String value = raw == null ? "" : raw;
        List<HmacCandidate> result = new ArrayList<>();
        configuration.hmacKeys().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(
                        new HmacCandidate(entry.getKey(), hmac(value, entry.getValue()))));
        return result;
    }

    private byte[] hmac(String raw, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw factorRejected();
        }
    }

    private void assertAttemptAllowed(
            OperatorEntity operator,
            AuthAttemptScope scope,
            String eventType,
            Instant now
    ) {
        Optional<OperatorAuthAttemptEntity> existing = attemptRepository
                .findByOperatorIdAndScopeForUpdate(operator.getId(), scope);
        if (existing.isEmpty()) {
            return;
        }
        OperatorAuthAttemptEntity attempt = existing.get();
        if (attempt.getBlockedUntil() != null && attempt.getBlockedUntil().isAfter(now)) {
            event(operator, eventType, "RATE_LIMITED", now);
            throw scope == AuthAttemptScope.RECOVERY ? recoveryRejected() : factorRejected();
        }
        if (!attempt.getWindowStartedAt().plus(
                properties.getRecovery().getAttemptWindow()).isAfter(now)) {
            attempt.setWindowStartedAt(now);
            attempt.setFailedCount(0);
            attempt.setBlockedUntil(null);
            attempt.setUpdatedAt(now);
            attemptRepository.saveAndFlush(attempt);
        }
    }

    private void recordFailure(
            OperatorEntity operator,
            AuthAttemptScope scope,
            Instant now
    ) {
        OperatorAuthAttemptEntity attempt = attemptRepository
                .findByOperatorIdAndScopeForUpdate(operator.getId(), scope)
                .orElseGet(() -> {
                    OperatorAuthAttemptEntity created = new OperatorAuthAttemptEntity();
                    created.setId(UUID.randomUUID());
                    created.setOperator(operator);
                    created.setScope(scope);
                    created.setWindowStartedAt(now);
                    created.setFailedCount(0);
                    created.setUpdatedAt(now);
                    return created;
                });
        if (!attempt.getWindowStartedAt().plus(
                properties.getRecovery().getAttemptWindow()).isAfter(now)) {
            attempt.setWindowStartedAt(now);
            attempt.setFailedCount(0);
            attempt.setBlockedUntil(null);
        }
        int failures = Math.addExact(attempt.getFailedCount(), 1);
        attempt.setFailedCount(failures);
        if (failures >= properties.getRecovery().getMaxAttempts()) {
            attempt.setBlockedUntil(now.plus(properties.getRecovery().getLockout()));
        }
        attempt.setUpdatedAt(now);
        attemptRepository.saveAndFlush(attempt);
    }

    private void clearAttempts(Long operatorId, AuthAttemptScope scope) {
        attemptRepository.findByOperatorIdAndScopeForUpdate(operatorId, scope)
                .ifPresent(attemptRepository::delete);
        attemptRepository.flush();
    }

    private void revokeRecoveryCodes(
            Long operatorId,
            String reason,
            Instant now,
            UUID excludedCodeId
    ) {
        List<OperatorRecoveryCodeEntity> codes = recoveryCodeRepository
                .findAllByOperatorIdForUpdate(operatorId);
        for (OperatorRecoveryCodeEntity code : codes) {
            if ((excludedCodeId == null || !excludedCodeId.equals(code.getId()))
                    && code.getConsumedAt() == null && code.getRevokedAt() == null) {
                code.setRevokedAt(now);
                code.setRevocationReason(reason);
            }
        }
        recoveryCodeRepository.saveAllAndFlush(codes);
    }

    private void terminalizeFactors(
            List<OperatorTotpFactorEntity> factors,
            TotpFactorState state,
            String reason,
            Instant now
    ) {
        factors.forEach(factor -> terminalizeFactor(factor, state, reason, now));
        factorRepository.saveAllAndFlush(factors);
    }

    private void terminalizeFactor(
            OperatorTotpFactorEntity factor,
            TotpFactorState state,
            String reason,
            Instant now
    ) {
        factor.setState(state);
        factor.setRevokedAt(now);
        factor.setRevocationReason(reason);
    }

    private void advanceCredentialVersion(OperatorEntity operator, Instant now) {
        try {
            operator.setCredentialVersion(Math.addExact(operator.getCredentialVersion(), 1L));
        } catch (ArithmeticException exception) {
            throw factorRejected();
        }
        operator.setUpdatedAt(now);
    }

    private OperatorSecurityEventEntity event(
            OperatorEntity operator,
            String eventType,
            String outcome,
            Instant now
    ) {
        OperatorSecurityEventEntity event = new OperatorSecurityEventEntity();
        event.setId(UUID.randomUUID());
        event.setOperator(operator);
        event.setEventType(eventType);
        event.setOutcome(outcome);
        event.setOccurredAt(now);
        return eventRepository.saveAndFlush(event);
    }

    private void notification(
            OperatorEntity operator,
            OperatorSecurityEventEntity event,
            Instant now
    ) {
        OperatorSecurityNotificationEntity notification =
                new OperatorSecurityNotificationEntity();
        notification.setId(UUID.randomUUID());
        notification.setSecurityEvent(event);
        notification.setOperator(operator);
        notification.setTemplateCode("ACCOUNT_RECOVERED");
        notification.setState("PENDING");
        notification.setCreatedAt(now);
        notificationRepository.saveAndFlush(notification);
    }

    private <T> T reject(
            OperatorEntity operator,
            AuthAttemptScope scope,
            String eventType,
            Instant now
    ) {
        recordFailure(operator, scope, now);
        event(operator, eventType, "REJECTED", now);
        throw scope == AuthAttemptScope.RECOVERY ? recoveryRejected() : factorRejected();
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }

    private byte[] factorBinding(Long operatorId, UUID enrollmentId) {
        return (operatorId + ":" + enrollmentId).getBytes(StandardCharsets.US_ASCII);
    }

    private String canonicalRecoveryCodeOrDummy(String value) {
        return value != null && RECOVERY_CODE.matcher(value).matches()
                ? value
                : "______________________";
    }

    private String base32(byte[] value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder encoded = new StringBuilder((value.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte item : value) {
            buffer = (buffer << 8) | (item & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(alphabet.charAt((buffer >>> bits) & 31));
            }
        }
        if (bits > 0) {
            encoded.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        }
        return encoded.toString();
    }

    private static OperatorAuthenticationException factorRejected() {
        return new OperatorAuthenticationException("Factor operation rejected");
    }

    private static OperatorAuthenticationException recoveryRejected() {
        return new OperatorAuthenticationException("Account recovery rejected");
    }

    private record KeyConfiguration(Map<String, byte[]> keys, String activeVersion) {
    }

    private record SecurityConfiguration(
            Map<String, byte[]> encryptionKeys,
            String activeEncryptionVersion,
            Map<String, byte[]> hmacKeys,
            String activeHmacVersion
    ) {
        private byte[] activeEncryptionKey() { return encryptionKeys.get(activeEncryptionVersion); }
        private byte[] activeHmacKey() { return hmacKeys.get(activeHmacVersion); }
    }

    private record HmacCandidate(String version, byte[] digest) {
        private HmacCandidate {
            digest = digest.clone();
        }
        @Override public byte[] digest() { return digest.clone(); }
    }
}
