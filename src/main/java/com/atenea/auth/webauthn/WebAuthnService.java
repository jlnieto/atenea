package com.atenea.auth.webauthn;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.auth.action.PrivilegedActionBinding;
import com.atenea.auth.action.PrivilegedActionFactor;
import com.atenea.auth.action.VerifiedStepUp;
import com.atenea.auth.recovery.AuthAttemptScope;
import com.atenea.persistence.auth.OperatorAuthAttemptEntity;
import com.atenea.persistence.auth.OperatorAuthAttemptRepository;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorSecurityEventEntity;
import com.atenea.persistence.auth.OperatorSecurityEventRepository;
import com.atenea.persistence.auth.OperatorSessionFamilyEntity;
import com.atenea.persistence.auth.OperatorSessionFamilyRepository;
import com.atenea.persistence.auth.OperatorWebAuthnChallengeEntity;
import com.atenea.persistence.auth.OperatorWebAuthnChallengeRepository;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialEntity;
import com.atenea.persistence.auth.OperatorWebAuthnCredentialRepository;
import com.atenea.persistence.auth.OperatorWebAuthnUserEntity;
import com.atenea.persistence.auth.OperatorWebAuthnUserRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebAuthnService {

    private static final int CHALLENGE_BYTES = 32;
    private static final int USER_HANDLE_BYTES = 32;
    private static final int MAX_CLIENT_DATA_BYTES = 4096;
    private static final int MAX_ATTESTATION_BYTES = 16384;
    private static final int MAX_AUTHENTICATOR_DATA_BYTES = 4096;
    private static final int MAX_SIGNATURE_BYTES = 1024;
    private static final int MAX_CREDENTIAL_ID_BYTES = 1024;
    private static final Set<Integer> ALLOWED_ALGORITHMS = Set.of(-7, -8, -257);
    private static final Set<String> ALLOWED_TRANSPORTS = Set.of(
            "ble", "hybrid", "internal", "nfc", "smart-card", "usb");
    private static final Pattern RP_ID = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");
    private static final Pattern ANDROID_ORIGIN = Pattern.compile(
            "^android:apk-key-hash:[A-Za-z0-9_-]{43}$");

    private final OperatorRepository operatorRepository;
    private final OperatorSessionFamilyRepository sessionFamilyRepository;
    private final OperatorWebAuthnUserRepository userRepository;
    private final OperatorWebAuthnCredentialRepository credentialRepository;
    private final OperatorWebAuthnChallengeRepository challengeRepository;
    private final OperatorAuthAttemptRepository attemptRepository;
    private final OperatorSecurityEventRepository eventRepository;
    private final OperatorAuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final WebAuthnManager webAuthnManager;
    private final AttestedCredentialDataConverter credentialDataConverter;

    public WebAuthnService(
            OperatorRepository operatorRepository,
            OperatorSessionFamilyRepository sessionFamilyRepository,
            OperatorWebAuthnUserRepository userRepository,
            OperatorWebAuthnCredentialRepository credentialRepository,
            OperatorWebAuthnChallengeRepository challengeRepository,
            OperatorAuthAttemptRepository attemptRepository,
            OperatorSecurityEventRepository eventRepository,
            OperatorAuthProperties properties
    ) {
        this.operatorRepository = operatorRepository;
        this.sessionFamilyRepository = sessionFamilyRepository;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.challengeRepository = challengeRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.properties = properties;
        ObjectConverter objectConverter = new ObjectConverter();
        webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
        webAuthnManager.getAuthenticationDataVerifier()
                .setMaliciousCounterValueHandler(authenticationObject -> { });
        credentialDataConverter = new AttestedCredentialDataConverter(objectConverter);
    }

    @Transactional
    public WebAuthnOptionsResponse beginRegistration(
            AuthenticatedOperator actor,
            UUID sessionFamilyId,
            WebAuthnChannel channel,
            String presentedOrigin
    ) {
        WebAuthnConfiguration configuration = configuration(channel, presentedOrigin);
        if (sessionFamilyId == null) {
            throw rejected();
        }
        OperatorEntity operator = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnService::rejected);
        OperatorSessionFamilyEntity family = sessionFamilyRepository
                .findByIdAndOperatorId(sessionFamilyId, operator.getId())
                .filter(candidate -> candidate.getRevokedAt() == null)
                .filter(candidate -> candidate.getAbsoluteExpiresAt().isAfter(Instant.now()))
                .orElseThrow(WebAuthnService::rejected);
        OperatorWebAuthnUserEntity user = userRepository.findById(operator.getId())
                .orElseGet(() -> createUser(operator));
        Challenge challenge = createChallenge(
                WebAuthnChallengePurpose.REGISTRATION,
                channel,
                configuration,
                operator,
                family,
                null);
        String handle = encode(user.getUserHandle());
        return new WebAuthnOptionsResponse(
                challenge.id(),
                encode(challenge.raw()),
                properties.getWebAuthn().getChallengeTtl().toMillis(),
                configuration.rpId(),
                properties.getWebAuthn().getRelyingPartyName(),
                handle,
                "operator-" + handle.substring(0, 12),
                credentialParameters(),
                List.of(),
                "required",
                "required",
                "none");
    }

    @Transactional
    public WebAuthnOptionsResponse beginAuthentication(
            WebAuthnChannel channel,
            String presentedOrigin
    ) {
        WebAuthnConfiguration configuration = configuration(channel, presentedOrigin);
        Challenge challenge = createChallenge(
                WebAuthnChallengePurpose.AUTHENTICATION,
                channel,
                configuration,
                null,
                null,
                null);
        return new WebAuthnOptionsResponse(
                challenge.id(),
                encode(challenge.raw()),
                properties.getWebAuthn().getChallengeTtl().toMillis(),
                configuration.rpId(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                "required",
                null,
                null);
    }

    @Transactional
    public void completeRegistration(
            AuthenticatedOperator actor,
            UUID sessionFamilyId,
            WebAuthnChannel channel,
            String presentedOrigin,
            WebAuthnRegistrationRequest request
    ) {
        WebAuthnConfiguration configuration = configuration(channel, presentedOrigin);
        byte[] clientData = decode(request.clientDataJson(), MAX_CLIENT_DATA_BYTES);
        byte[] attestationObject = decode(request.attestationObject(), MAX_ATTESTATION_BYTES);
        byte[] submittedCredentialId = decode(request.credentialId(), MAX_CREDENTIAL_ID_BYTES);
        Set<String> transports = normalizeTransports(request.transports());
        RegistrationData registrationData = parseRegistration(
                new RegistrationRequest(attestationObject, clientData, transports));
        byte[] rawChallenge = registrationData.getCollectedClientData().getChallenge().getValue();
        OperatorWebAuthnChallengeEntity challenge = lockChallenge(
                request.requestId(),
                rawChallenge,
                WebAuthnChallengePurpose.REGISTRATION,
                channel,
                configuration,
                actor.operatorId(),
                sessionFamilyId);
        verifyRegistration(registrationData, rawChallenge, configuration);

        AttestedCredentialData attested = registrationData.getAttestationObject()
                .getAuthenticatorData().getAttestedCredentialData();
        if (attested == null
                || !MessageDigest.isEqual(attested.getCredentialId(), submittedCredentialId)
                || credentialRepository.findByCredentialId(submittedCredentialId).isPresent()) {
            throw rejected();
        }
        int algorithm = Math.toIntExact(attested.getCOSEKey().getAlgorithm().getValue());
        if (!ALLOWED_ALGORITHMS.contains(algorithm)) {
            throw rejected();
        }
        OperatorEntity operator = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnService::rejected);
        OperatorWebAuthnUserEntity user = userRepository.findById(operator.getId())
                .orElseThrow(WebAuthnService::rejected);
        if (user.getUserHandle() == null) {
            throw rejected();
        }

        AuthenticatorData<?> authenticatorData = registrationData.getAttestationObject()
                .getAuthenticatorData();
        OperatorWebAuthnCredentialEntity credential = new OperatorWebAuthnCredentialEntity();
        credential.setId(UUID.randomUUID());
        credential.setOperator(operator);
        credential.setCredentialId(submittedCredentialId);
        credential.setPublicKeyCose(extractCoseKey(attested));
        credential.setAlgorithm(algorithm);
        credential.setAaguid(attested.getAaguid().getValue());
        credential.setSignCount(authenticatorData.getSignCount());
        credential.setTransports(String.join(",", transports));
        credential.setBackupEligible(authenticatorData.isFlagBE());
        credential.setBackupState(authenticatorData.isFlagBS());
        credential.setCreatedAt(Instant.now());
        try {
            credentialRepository.saveAndFlush(credential);
        } catch (DataIntegrityViolationException exception) {
            throw rejected();
        }
        advanceCredentialVersion(operator);
        consume(challenge);
    }

    @Transactional
    public AuthenticatedOperator completeAuthentication(
            WebAuthnChannel channel,
            String presentedOrigin,
            WebAuthnAuthenticationRequest request
    ) {
        WebAuthnConfiguration configuration = configuration(channel, presentedOrigin);
        byte[] credentialId = decode(request.credentialId(), MAX_CREDENTIAL_ID_BYTES);
        byte[] userHandle = decodeExact(request.userHandle(), USER_HANDLE_BYTES);
        byte[] authenticatorData = decode(
                request.authenticatorData(), MAX_AUTHENTICATOR_DATA_BYTES);
        byte[] clientData = decode(request.clientDataJson(), MAX_CLIENT_DATA_BYTES);
        byte[] signature = decode(request.signature(), MAX_SIGNATURE_BYTES);
        OperatorWebAuthnCredentialEntity credential = credentialRepository
                .findByCredentialIdForUpdate(credentialId)
                .filter(candidate -> candidate.getRevokedAt() == null)
                .filter(candidate -> candidate.getOperator().isActive())
                .orElseThrow(WebAuthnService::rejected);
        OperatorWebAuthnUserEntity user = userRepository.findByUserHandle(userHandle)
                .filter(candidate -> candidate.getOperatorId().equals(
                        credential.getOperator().getId()))
                .orElseThrow(WebAuthnService::rejected);
        if (!MessageDigest.isEqual(user.getUserHandle(), userHandle)) {
            throw rejected();
        }

        AuthenticationData parsed = parseAuthentication(new AuthenticationRequest(
                credentialId, userHandle, authenticatorData, clientData, signature));
        byte[] rawChallenge = parsed.getCollectedClientData().getChallenge().getValue();
        OperatorWebAuthnChallengeEntity challenge = lockChallenge(
                request.requestId(),
                rawChallenge,
                WebAuthnChallengePurpose.AUTHENTICATION,
                channel,
                configuration,
                null,
                null);
        CredentialRecordImpl record = credentialRecord(credential);
        verifyAuthentication(parsed, rawChallenge, configuration, credentialId, record);
        long presentedCounter = parsed.getAuthenticatorData().getSignCount();
        updateCounterAndBackupState(credential, parsed, presentedCounter);
        credential.setLastUsedAt(Instant.now());
        credentialRepository.saveAndFlush(credential);
        consume(challenge);
        OperatorEntity operator = credential.getOperator();
        return new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
    }

    @Transactional
    public WebAuthnOptionsResponse beginStepUp(
            AuthenticatedOperator actor,
            UUID sessionFamilyId,
            WebAuthnChannel channel,
            String presentedOrigin,
            PrivilegedActionBinding binding
    ) {
        WebAuthnConfiguration configuration = configuration(channel, presentedOrigin);
        if (sessionFamilyId == null || binding == null) throw rejected();
        OperatorEntity operator = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnService::rejected);
        assertStepUpAllowed(operator, Instant.now());
        OperatorSessionFamilyEntity family = sessionFamilyRepository
                .findByIdAndOperatorId(sessionFamilyId, operator.getId())
                .filter(candidate -> candidate.getRevokedAt() == null)
                .filter(candidate -> candidate.getAbsoluteExpiresAt().isAfter(Instant.now()))
                .orElseThrow(WebAuthnService::rejected);
        List<WebAuthnOptionsResponse.CredentialDescriptor> credentials = credentialRepository
                .findAllByOperatorIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(operator.getId())
                .stream()
                .map(credential -> new WebAuthnOptionsResponse.CredentialDescriptor(
                        "public-key", encode(credential.getCredentialId()),
                        credential.getTransports().isBlank()
                                ? List.of()
                                : List.of(credential.getTransports().split(","))))
                .toList();
        if (credentials.isEmpty()) throw rejected();
        Challenge challenge = createChallenge(
                WebAuthnChallengePurpose.STEP_UP, channel, configuration,
                operator, family, binding);
        return new WebAuthnOptionsResponse(
                challenge.id(), encode(challenge.raw()),
                properties.getWebAuthn().getChallengeTtl().toMillis(),
                configuration.rpId(), null, null, null, List.of(), credentials,
                "required", null, null);
    }

    @Transactional(noRollbackFor = OperatorAuthenticationException.class)
    public VerifiedStepUp completeStepUp(
            AuthenticatedOperator actor,
            UUID sessionFamilyId,
            WebAuthnChannel channel,
            String presentedOrigin,
            PrivilegedActionBinding binding,
            WebAuthnAuthenticationRequest request
    ) {
        Instant now = Instant.now();
        OperatorEntity attempted = operatorRepository.findByIdForUpdate(actor.operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(WebAuthnService::rejected);
        assertStepUpAllowed(attempted, now);
        try {
            WebAuthnConfiguration configuration = configuration(channel, presentedOrigin);
            byte[] credentialId = decode(request.credentialId(), MAX_CREDENTIAL_ID_BYTES);
            byte[] userHandle = decodeExact(request.userHandle(), USER_HANDLE_BYTES);
            byte[] authenticatorData = decode(request.authenticatorData(), MAX_AUTHENTICATOR_DATA_BYTES);
            byte[] clientData = decode(request.clientDataJson(), MAX_CLIENT_DATA_BYTES);
            byte[] signature = decode(request.signature(), MAX_SIGNATURE_BYTES);
            OperatorWebAuthnCredentialEntity credential = credentialRepository
                    .findByCredentialIdForUpdate(credentialId)
                    .filter(candidate -> candidate.getRevokedAt() == null)
                    .filter(candidate -> candidate.getOperator().isActive())
                    .filter(candidate -> candidate.getOperator().getId().equals(actor.operatorId()))
                    .orElseThrow(WebAuthnService::rejected);
            OperatorWebAuthnUserEntity user = userRepository.findByUserHandle(userHandle)
                    .filter(candidate -> candidate.getOperatorId().equals(actor.operatorId()))
                    .orElseThrow(WebAuthnService::rejected);
            if (!MessageDigest.isEqual(user.getUserHandle(), userHandle)) throw rejected();
            AuthenticationData parsed = parseAuthentication(new AuthenticationRequest(
                    credentialId, userHandle, authenticatorData, clientData, signature));
            byte[] rawChallenge = parsed.getCollectedClientData().getChallenge().getValue();
            OperatorWebAuthnChallengeEntity challenge = lockChallenge(
                    request.requestId(), rawChallenge, WebAuthnChallengePurpose.STEP_UP,
                    channel, configuration, actor.operatorId(), sessionFamilyId, binding);
            verifyAuthentication(parsed, rawChallenge, configuration, credentialId,
                    credentialRecord(credential));
            updateCounterAndBackupState(credential, parsed,
                    parsed.getAuthenticatorData().getSignCount());
            now = Instant.now();
            credential.setLastUsedAt(now);
            credentialRepository.saveAndFlush(credential);
            consume(challenge);
            clearStepUpAttempts(attempted.getId());
            securityEvent(attempted, "STEP_UP_VERIFIED", "SUCCEEDED", now);
            return new VerifiedStepUp(actor.operatorId(), sessionFamilyId, binding,
                    PrivilegedActionFactor.WEBAUTHN, now);
        } catch (OperatorAuthenticationException exception) {
            recordStepUpFailure(attempted, now);
            securityEvent(attempted, "STEP_UP_REJECTED", "REJECTED", now);
            throw exception;
        }
    }

    private void assertStepUpAllowed(OperatorEntity operator, Instant now) {
        OperatorAuthProperties.Recovery configured = properties.getRecovery();
        if (configured.getMaxAttempts() < 1 || configured.getMaxAttempts() > 20
                || invalidAttemptDuration(configured.getAttemptWindow(), Duration.ofHours(1))
                || invalidAttemptDuration(configured.getLockout(), Duration.ofDays(1))) {
            throw rejected();
        }
        attemptRepository.findByOperatorIdAndScopeForUpdate(
                operator.getId(), AuthAttemptScope.STEP_UP).ifPresent(attempt -> {
                    if (attempt.getBlockedUntil() != null && attempt.getBlockedUntil().isAfter(now)) {
                        securityEvent(operator, "STEP_UP_RATE_LIMITED", "RATE_LIMITED", now);
                        throw rejected();
                    }
                    if (!attempt.getWindowStartedAt().plus(
                            configured.getAttemptWindow()).isAfter(now)) {
                        attempt.setWindowStartedAt(now);
                        attempt.setFailedCount(0);
                        attempt.setBlockedUntil(null);
                        attempt.setUpdatedAt(now);
                        attemptRepository.saveAndFlush(attempt);
                    }
                });
    }

    private void recordStepUpFailure(OperatorEntity operator, Instant now) {
        OperatorAuthProperties.Recovery configured = properties.getRecovery();
        OperatorAuthAttemptEntity attempt = attemptRepository
                .findByOperatorIdAndScopeForUpdate(operator.getId(), AuthAttemptScope.STEP_UP)
                .orElseGet(() -> {
                    OperatorAuthAttemptEntity created = new OperatorAuthAttemptEntity();
                    created.setId(UUID.randomUUID());
                    created.setOperator(operator);
                    created.setScope(AuthAttemptScope.STEP_UP);
                    created.setWindowStartedAt(now);
                    created.setFailedCount(0);
                    created.setUpdatedAt(now);
                    return created;
                });
        if (!attempt.getWindowStartedAt().plus(configured.getAttemptWindow()).isAfter(now)) {
            attempt.setWindowStartedAt(now);
            attempt.setFailedCount(0);
            attempt.setBlockedUntil(null);
        }
        int failures = Math.addExact(attempt.getFailedCount(), 1);
        attempt.setFailedCount(failures);
        if (failures >= configured.getMaxAttempts()) {
            attempt.setBlockedUntil(now.plus(configured.getLockout()));
        }
        attempt.setUpdatedAt(now);
        attemptRepository.saveAndFlush(attempt);
    }

    private void clearStepUpAttempts(Long operatorId) {
        attemptRepository.findByOperatorIdAndScopeForUpdate(operatorId, AuthAttemptScope.STEP_UP)
                .ifPresent(attemptRepository::delete);
        attemptRepository.flush();
    }

    private boolean invalidAttemptDuration(Duration value, Duration maximum) {
        return value == null || value.isZero() || value.isNegative()
                || value.compareTo(maximum) > 0;
    }

    private void securityEvent(
            OperatorEntity operator, String eventType, String outcome, Instant now) {
        OperatorSecurityEventEntity event = new OperatorSecurityEventEntity();
        event.setId(UUID.randomUUID());
        event.setOperator(operator);
        event.setEventType(eventType);
        event.setOutcome(outcome);
        event.setOccurredAt(now);
        eventRepository.saveAndFlush(event);
    }

    private RegistrationData parseRegistration(RegistrationRequest request) {
        try {
            RegistrationData data = webAuthnManager.parse(request);
            if (data.getCollectedClientData() == null
                    || data.getAttestationObject() == null) {
                throw rejected();
            }
            return data;
        } catch (DataConversionException | IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private AuthenticationData parseAuthentication(AuthenticationRequest request) {
        try {
            AuthenticationData data = webAuthnManager.parse(request);
            if (data.getCollectedClientData() == null || data.getAuthenticatorData() == null) {
                throw rejected();
            }
            return data;
        } catch (DataConversionException | IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private void verifyRegistration(
            RegistrationData data,
            byte[] challenge,
            WebAuthnConfiguration configuration
    ) {
        try {
            webAuthnManager.verify(data, new RegistrationParameters(
                    serverProperty(configuration, challenge),
                    List.of(
                            new PublicKeyCredentialParameters(
                                    PublicKeyCredentialType.PUBLIC_KEY,
                                    COSEAlgorithmIdentifier.ES256),
                            new PublicKeyCredentialParameters(
                                    PublicKeyCredentialType.PUBLIC_KEY,
                                    COSEAlgorithmIdentifier.EdDSA),
                            new PublicKeyCredentialParameters(
                                    PublicKeyCredentialType.PUBLIC_KEY,
                                    COSEAlgorithmIdentifier.RS256)),
                    true,
                    true));
        } catch (VerificationException | IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private void verifyAuthentication(
            AuthenticationData data,
            byte[] challenge,
            WebAuthnConfiguration configuration,
            byte[] credentialId,
            CredentialRecordImpl record
    ) {
        try {
            webAuthnManager.verify(data, new AuthenticationParameters(
                    serverProperty(configuration, challenge),
                    record,
                    List.of(credentialId),
                    true,
                    true));
        } catch (VerificationException | IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private OperatorWebAuthnChallengeEntity lockChallenge(
            UUID requestId,
            byte[] rawChallenge,
            WebAuthnChallengePurpose purpose,
            WebAuthnChannel channel,
            WebAuthnConfiguration configuration,
            Long operatorId,
            UUID sessionFamilyId
    ) {
        return lockChallenge(requestId, rawChallenge, purpose, channel, configuration,
                operatorId, sessionFamilyId, null);
    }

    private OperatorWebAuthnChallengeEntity lockChallenge(
            UUID requestId,
            byte[] rawChallenge,
            WebAuthnChallengePurpose purpose,
            WebAuthnChannel channel,
            WebAuthnConfiguration configuration,
            Long operatorId,
            UUID sessionFamilyId,
            PrivilegedActionBinding binding
    ) {
        if (rawChallenge == null || rawChallenge.length != CHALLENGE_BYTES) {
            throw rejected();
        }
        OperatorWebAuthnChallengeEntity challenge = challengeRepository
                .findByIdForUpdate(requestId)
                .orElseThrow(WebAuthnService::rejected);
        Instant now = Instant.now();
        boolean actorMatches = operatorId == null
                ? challenge.getOperator() == null
                : challenge.getOperator() != null
                        && operatorId.equals(challenge.getOperator().getId());
        boolean familyMatches = sessionFamilyId == null
                ? challenge.getSessionFamily() == null
                : challenge.getSessionFamily() != null
                        && sessionFamilyId.equals(challenge.getSessionFamily().getId());
        boolean bindingMatches = binding == null
                ? challenge.getActionKind() == null
                        && challenge.getTargetFingerprint() == null
                        && challenge.getPlanFingerprint() == null
                : binding.actionKind().equals(challenge.getActionKind())
                        && MessageDigest.isEqual(binding.targetFingerprint(),
                                challenge.getTargetFingerprint())
                        && MessageDigest.isEqual(binding.planFingerprint(),
                                challenge.getPlanFingerprint());
        if (challenge.getConsumedAt() != null
                || !challenge.getExpiresAt().isAfter(now)
                || challenge.getPurpose() != purpose
                || challenge.getChannel() != channel
                || !challenge.getRelyingPartyId().equals(configuration.rpId())
                || !challenge.getExpectedOrigin().equals(configuration.origin())
                || !actorMatches
                || !familyMatches
                || !bindingMatches
                || !MessageDigest.isEqual(
                        challenge.getChallengeDigest(), sha256(rawChallenge))) {
            throw rejected();
        }
        return challenge;
    }

    private CredentialRecordImpl credentialRecord(OperatorWebAuthnCredentialEntity credential) {
        AttestedCredentialData attested = credentialDataConverter.convert(concatenate(
                new AAGUID(credential.getAaguid()).getBytes(),
                unsignedShort(credential.getCredentialId().length),
                credential.getCredentialId(),
                credential.getPublicKeyCose()));
        CredentialRecordImpl record = new CredentialRecordImpl(
                new NoneAttestationStatement(),
                Boolean.TRUE,
                credential.isBackupEligible(),
                credential.isBackupState(),
                credential.getSignCount(),
                attested,
                new AuthenticationExtensionsAuthenticatorOutputs<RegistrationExtensionAuthenticatorOutput>(),
                null,
                null,
                parseTransports(credential.getTransports()));
        return record;
    }

    private void updateCounterAndBackupState(
            OperatorWebAuthnCredentialEntity credential,
            AuthenticationData data,
            long presentedCounter
    ) {
        long storedCounter = credential.getSignCount();
        boolean regression = (presentedCounter > 0 || storedCounter > 0)
                && presentedCounter <= storedCounter;
        if (regression && !credential.isBackupEligible()) {
            throw rejected();
        }
        if (presentedCounter > storedCounter) {
            credential.setSignCount(presentedCounter);
        }
        boolean backupEligible = data.getAuthenticatorData().isFlagBE();
        boolean backupState = data.getAuthenticatorData().isFlagBS();
        if (backupEligible != credential.isBackupEligible()
                || (backupState && !backupEligible)) {
            throw rejected();
        }
        credential.setBackupState(backupState);
    }

    private OperatorWebAuthnUserEntity createUser(OperatorEntity operator) {
        OperatorWebAuthnUserEntity user = new OperatorWebAuthnUserEntity();
        user.setOperatorId(operator.getId());
        user.setUserHandle(randomBytes(USER_HANDLE_BYTES));
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.saveAndFlush(user);
    }

    private Challenge createChallenge(
            WebAuthnChallengePurpose purpose,
            WebAuthnChannel channel,
            WebAuthnConfiguration configuration,
            OperatorEntity operator,
            OperatorSessionFamilyEntity family,
            PrivilegedActionBinding binding
    ) {
        Duration ttl = properties.getWebAuthn().getChallengeTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw rejected();
        }
        byte[] raw = randomBytes(CHALLENGE_BYTES);
        Instant now = Instant.now();
        OperatorWebAuthnChallengeEntity challenge = new OperatorWebAuthnChallengeEntity();
        challenge.setId(UUID.randomUUID());
        challenge.setChallengeDigest(sha256(raw));
        challenge.setPurpose(purpose);
        challenge.setChannel(channel);
        challenge.setOperator(operator);
        challenge.setSessionFamily(family);
        challenge.setRelyingPartyId(configuration.rpId());
        challenge.setExpectedOrigin(configuration.origin());
        if (binding != null) {
            challenge.setActionKind(binding.actionKind());
            challenge.setTargetFingerprint(binding.targetFingerprint());
            challenge.setPlanFingerprint(binding.planFingerprint());
        }
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plus(ttl));
        challengeRepository.saveAndFlush(challenge);
        return new Challenge(challenge.getId(), raw);
    }

    private void consume(OperatorWebAuthnChallengeEntity challenge) {
        challenge.setConsumedAt(Instant.now());
        challengeRepository.saveAndFlush(challenge);
    }

    private void advanceCredentialVersion(OperatorEntity operator) {
        try {
            operator.setCredentialVersion(Math.addExact(operator.getCredentialVersion(), 1L));
        } catch (ArithmeticException exception) {
            throw rejected();
        }
        operator.setUpdatedAt(Instant.now());
        operatorRepository.saveAndFlush(operator);
    }

    private WebAuthnConfiguration configuration(
            WebAuthnChannel channel,
            String presentedOrigin
    ) {
        OperatorAuthProperties.WebAuthn configured = properties.getWebAuthn();
        if (!configured.isEnabled()) {
            throw rejected();
        }
        String rpId = configured.getRelyingPartyId();
        String rpName = configured.getRelyingPartyName();
        if (rpId == null || !RP_ID.matcher(rpId).matches()
                || rpName == null || rpName.isBlank() || !rpName.equals(rpName.trim())
                || presentedOrigin == null || presentedOrigin.isBlank()
                || !presentedOrigin.equals(presentedOrigin.trim())) {
            throw rejected();
        }
        String expected;
        if (channel == WebAuthnChannel.WEB) {
            expected = configured.getWebOrigin();
            if (!"atenea.yudri.es".equals(rpId)
                    || !"https://atenea.yudri.es".equals(expected)
                    || !expected.equals(presentedOrigin)) {
                throw rejected();
            }
        } else if (channel == WebAuthnChannel.ANDROID) {
            List<String> origins = configured.getAndroidOrigins();
            if (origins == null || origins.isEmpty()
                    || origins.stream().anyMatch(origin -> origin == null
                            || !ANDROID_ORIGIN.matcher(origin).matches())
                    || origins.stream().distinct().count() != origins.size()
                    || !origins.contains(presentedOrigin)) {
                throw rejected();
            }
            expected = presentedOrigin;
        } else {
            throw rejected();
        }
        return new WebAuthnConfiguration(rpId, expected);
    }

    private ServerProperty serverProperty(
            WebAuthnConfiguration configuration,
            byte[] challenge
    ) {
        return new ServerProperty(
                new Origin(configuration.origin()),
                configuration.rpId(),
                new DefaultChallenge(challenge));
    }

    private List<WebAuthnOptionsResponse.CredentialParameter> credentialParameters() {
        return List.of(
                new WebAuthnOptionsResponse.CredentialParameter("public-key", -7),
                new WebAuthnOptionsResponse.CredentialParameter("public-key", -8),
                new WebAuthnOptionsResponse.CredentialParameter("public-key", -257));
    }

    private Set<String> normalizeTransports(Set<String> transports) {
        if (transports == null || transports.isEmpty()) {
            return Set.of();
        }
        if (transports.stream().anyMatch(value -> value == null
                || !ALLOWED_TRANSPORTS.contains(value))) {
            throw rejected();
        }
        return java.util.Collections.unmodifiableSortedSet(
                new java.util.TreeSet<>(transports));
    }

    private Set<com.webauthn4j.data.AuthenticatorTransport> parseTransports(String value) {
        return parseTransportNames(value).stream()
                .map(com.webauthn4j.data.AuthenticatorTransport::create)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<String> parseTransportNames(String value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        return java.util.Collections.unmodifiableSortedSet(
                new java.util.TreeSet<>(java.util.Arrays.asList(value.split(",", -1))));
    }

    private byte[] extractCoseKey(AttestedCredentialData attested) {
        byte[] encoded = credentialDataConverter.convert(attested);
        int offset = 16 + 2 + attested.getCredentialId().length;
        return java.util.Arrays.copyOfRange(encoded, offset, encoded.length);
    }

    private byte[] decode(String value, int maxBytes) {
        if (value == null || value.isBlank() || value.indexOf('=') >= 0) {
            throw rejected();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length == 0 || decoded.length > maxBytes
                    || !encode(decoded).equals(value)) {
                throw rejected();
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private byte[] decodeExact(String value, int expectedBytes) {
        byte[] decoded = decode(value, expectedBytes);
        if (decoded.length != expectedBytes) {
            throw rejected();
        }
        return decoded;
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private byte[] unsignedShort(int value) {
        if (value < 0 || value > 65535) {
            throw rejected();
        }
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }

    private byte[] concatenate(byte[]... values) {
        int length = java.util.Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        java.util.Arrays.stream(values).forEach(buffer::put);
        return buffer.array();
    }

    private static OperatorAuthenticationException rejected() {
        return new OperatorAuthenticationException("WebAuthn ceremony rejected");
    }

    private record WebAuthnConfiguration(String rpId, String origin) {
    }

    private record Challenge(UUID id, byte[] raw) {
        private Challenge {
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }
}
