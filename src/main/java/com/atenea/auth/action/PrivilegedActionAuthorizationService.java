package com.atenea.auth.action;

import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.OperatorAuthenticationException;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorPrivilegedActionAuthorizationEntity;
import com.atenea.persistence.auth.OperatorPrivilegedActionAuthorizationRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.auth.OperatorSecurityEventEntity;
import com.atenea.persistence.auth.OperatorSecurityEventRepository;
import com.atenea.persistence.auth.OperatorSessionFamilyEntity;
import com.atenea.persistence.auth.OperatorSessionFamilyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrivilegedActionAuthorizationService {
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final Duration MAX_PROOF_AGE = Duration.ofMinutes(5);

    private final OperatorRepository operatorRepository;
    private final OperatorSessionFamilyRepository familyRepository;
    private final OperatorPrivilegedActionAuthorizationRepository authorizationRepository;
    private final OperatorSecurityEventRepository eventRepository;
    private final OperatorAuthProperties properties;

    public PrivilegedActionAuthorizationService(
            OperatorRepository operatorRepository,
            OperatorSessionFamilyRepository familyRepository,
            OperatorPrivilegedActionAuthorizationRepository authorizationRepository,
            OperatorSecurityEventRepository eventRepository,
            OperatorAuthProperties properties
    ) {
        this.operatorRepository = operatorRepository;
        this.familyRepository = familyRepository;
        this.authorizationRepository = authorizationRepository;
        this.eventRepository = eventRepository;
        this.properties = properties;
    }

    @Transactional
    public PrivilegedActionAuthorizationGrant issueVerified(VerifiedStepUp proof) {
        Duration ttl = configuration();
        Instant now = Instant.now();
        if (proof == null || proof.binding() == null || proof.factor() == null
                || proof.authenticatedAt() == null
                || proof.authenticatedAt().isAfter(now)
                || proof.authenticatedAt().plus(MAX_PROOF_AGE).isBefore(now)) {
            throw rejected();
        }
        OperatorEntity operator = operatorRepository.findByIdForUpdate(proof.operatorId())
                .filter(OperatorEntity::isActive)
                .filter(candidate -> candidate.getCodexOperationsRole()
                        == CodexOperationsRole.PLATFORM_ADMINISTRATOR)
                .filter(candidate -> !candidate.isFactorReenrollmentRequired())
                .orElseThrow(PrivilegedActionAuthorizationService::rejected);
        OperatorSessionFamilyEntity family = familyRepository.findByIdForUpdate(
                        proof.sessionFamilyId())
                .filter(candidate -> candidate.getOperator().getId().equals(operator.getId()))
                .filter(candidate -> candidate.getRevokedAt() == null)
                .filter(candidate -> candidate.getAbsoluteExpiresAt().isAfter(now))
                .orElseThrow(PrivilegedActionAuthorizationService::rejected);

        UUID rawAuthorization = UUID.randomUUID();
        OperatorPrivilegedActionAuthorizationEntity entity =
                new OperatorPrivilegedActionAuthorizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setAuthorizationDigest(digest(rawAuthorization));
        entity.setOperator(operator);
        entity.setSessionFamily(family);
        entity.setActionKind(proof.binding().actionKind());
        entity.setTargetFingerprint(proof.binding().targetFingerprint());
        entity.setPlanFingerprint(proof.binding().planFingerprint());
        entity.setFactor(proof.factor());
        entity.setAuthenticatedAt(proof.authenticatedAt());
        entity.setCredentialVersion(operator.getCredentialVersion());
        entity.setRoleVersion(operator.getRoleVersion());
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(ttl));
        try {
            authorizationRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw rejected();
        }
        event(operator, "ACTION_AUTH_ISSUED", "SUCCEEDED", now);
        return new PrivilegedActionAuthorizationGrant(rawAuthorization, entity.getExpiresAt());
    }

    @Transactional
    public <T> T consumeForAcceptance(
            UUID rawAuthorization,
            AuthenticatedSession session,
            PrivilegedActionBinding expected,
            PrivilegedActionAcceptance<T> acceptance
    ) {
        configuration();
        if (rawAuthorization == null || session == null || session.operator() == null
                || session.sessionFamilyId() == null || expected == null || acceptance == null) {
            throw rejected();
        }
        Instant now = Instant.now();
        OperatorPrivilegedActionAuthorizationEntity authorization = authorizationRepository
                .findByDigestForUpdate(digest(rawAuthorization))
                .orElseThrow(PrivilegedActionAuthorizationService::rejected);
        OperatorEntity operator = operatorRepository.findByIdForUpdate(
                        session.operator().operatorId())
                .filter(OperatorEntity::isActive)
                .orElseThrow(PrivilegedActionAuthorizationService::rejected);
        OperatorSessionFamilyEntity family = familyRepository.findByIdForUpdate(
                        session.sessionFamilyId())
                .filter(candidate -> candidate.getOperator().getId().equals(operator.getId()))
                .filter(candidate -> candidate.getRevokedAt() == null)
                .filter(candidate -> candidate.getAbsoluteExpiresAt().isAfter(now))
                .orElseThrow(PrivilegedActionAuthorizationService::rejected);
        if (authorization.getConsumedAt() != null
                || !authorization.getExpiresAt().isAfter(now)
                || !authorization.getOperator().getId().equals(operator.getId())
                || !authorization.getSessionFamily().getId().equals(family.getId())
                || operator.getCodexOperationsRole()
                        != CodexOperationsRole.PLATFORM_ADMINISTRATOR
                || authorization.getCredentialVersion() != operator.getCredentialVersion()
                || authorization.getRoleVersion() != operator.getRoleVersion()
                || !authorization.getActionKind().equals(expected.actionKind())
                || !MessageDigest.isEqual(authorization.getTargetFingerprint(),
                        expected.targetFingerprint())
                || !MessageDigest.isEqual(authorization.getPlanFingerprint(),
                        expected.planFingerprint())) {
            event(operator, "ACTION_AUTH_REJECTED", "REJECTED", now);
            throw rejected();
        }

        T result = acceptance.accept();
        authorization.setConsumedAt(now);
        authorizationRepository.saveAndFlush(authorization);
        event(operator, "ACTION_AUTH_CONSUMED", "SUCCEEDED", now);
        return result;
    }

    public boolean enforcementEnabled() {
        return properties.getPrivilegedActions().isEnforcementEnabled();
    }

    private Duration configuration() {
        OperatorAuthProperties.PrivilegedActions configured = properties.getPrivilegedActions();
        Duration ttl = configured.getAuthorizationTtl();
        if (!configured.isEnabled() || ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(MAX_TTL) > 0) {
            throw rejected();
        }
        return ttl;
    }

    private byte[] digest(UUID authorization) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    authorization.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw rejected();
        }
    }

    private void event(OperatorEntity operator, String type, String outcome, Instant now) {
        OperatorSecurityEventEntity event = new OperatorSecurityEventEntity();
        event.setId(UUID.randomUUID());
        event.setOperator(operator);
        event.setEventType(type);
        event.setOutcome(outcome);
        event.setOccurredAt(now);
        eventRepository.saveAndFlush(event);
    }

    private static OperatorAuthenticationException rejected() {
        return new OperatorAuthenticationException("Privileged action authorization rejected");
    }
}
