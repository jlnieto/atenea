package com.atenea.service.worksession;

import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionAcceptanceService {

    private static final String VALIDATION_REQUIRED_ACTION =
            "Run the required mediated validation for the current source tree";

    private final WorkSessionRepository workSessionRepository;

    public WorkSessionAcceptanceService(WorkSessionRepository workSessionRepository) {
        this.workSessionRepository = workSessionRepository;
    }

    @Transactional
    public WorkSessionAcceptanceSnapshot observeSourceTree(Long sessionId, String fingerprintSha256) {
        String fingerprint = requireHash(fingerprintSha256, "source tree fingerprint");
        WorkSessionEntity session = locked(sessionId);
        if (!fingerprint.equals(session.getSourceTreeFingerprintSha256())) {
            clearValidation(session);
            session.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
            session.setSourceTreeFingerprintSha256(fingerprint);
            session.setSourceTreeObservedAt(Instant.now());
            session.setAcceptanceNextAction(VALIDATION_REQUIRED_ACTION);
            session.setUpdatedAt(Instant.now());
            workSessionRepository.save(session);
        }
        return snapshot(session);
    }

    @Transactional
    public WorkSessionAcceptanceSnapshot markValidating(
            Long sessionId,
            String fingerprintSha256,
            String projectionSha256,
            String definitionRevision
    ) {
        WorkSessionEntity session = locked(sessionId);
        requireCurrentTree(session, fingerprintSha256);
        session.setAcceptanceState(WorkSessionAcceptanceState.VALIDATING);
        session.setValidationProjectionSha256(requireHash(projectionSha256, "validation projection"));
        session.setValidationDefinitionRevision(requireRevision(definitionRevision));
        session.setAcceptanceBlockedCheck(null);
        session.setAcceptanceNextAction("Wait for the bounded mediated checks to finish");
        session.setValidatedAt(null);
        session.setIntegrationReadyAt(null);
        session.setUpdatedAt(Instant.now());
        return snapshot(workSessionRepository.save(session));
    }

    @Transactional
    public WorkSessionAcceptanceSnapshot markBlocked(
            Long sessionId,
            String fingerprintSha256,
            String projectionSha256,
            String definitionRevision,
            String blockedCheck,
            String nextAction
    ) {
        WorkSessionEntity session = locked(sessionId);
        requireCurrentTree(session, fingerprintSha256);
        session.setAcceptanceState(WorkSessionAcceptanceState.BLOCKED);
        session.setValidationProjectionSha256(requireHash(projectionSha256, "validation projection"));
        session.setValidationDefinitionRevision(requireRevision(definitionRevision));
        session.setAcceptanceBlockedCheck(requireText(blockedCheck, 80, "blocked check"));
        session.setAcceptanceNextAction(requireText(nextAction, 240, "next action"));
        session.setValidatedAt(null);
        session.setIntegrationReadyAt(null);
        session.setUpdatedAt(Instant.now());
        return snapshot(workSessionRepository.save(session));
    }

    @Transactional
    public WorkSessionAcceptanceSnapshot markValidated(
            Long sessionId,
            String fingerprintSha256,
            String projectionSha256,
            String definitionRevision
    ) {
        WorkSessionEntity session = locked(sessionId);
        requireCurrentTree(session, fingerprintSha256);
        if (session.getAcceptanceState() != WorkSessionAcceptanceState.VALIDATING) {
            throw blocked("Validation can complete only from VALIDATING");
        }
        Instant now = Instant.now();
        session.setAcceptanceState(WorkSessionAcceptanceState.VALIDATED);
        session.setValidationProjectionSha256(requireHash(projectionSha256, "validation projection"));
        session.setValidationDefinitionRevision(requireRevision(definitionRevision));
        session.setAcceptanceBlockedCheck(null);
        session.setAcceptanceNextAction("Complete freshness and review gates");
        session.setValidatedAt(now);
        session.setIntegrationReadyAt(null);
        session.setUpdatedAt(now);
        return snapshot(workSessionRepository.save(session));
    }

    @Transactional
    public WorkSessionAcceptanceSnapshot markIntegrationReady(
            Long sessionId,
            String fingerprintSha256,
            String projectionSha256,
            String definitionRevision
    ) {
        WorkSessionEntity session = locked(sessionId);
        requireCurrentTree(session, fingerprintSha256);
        if (session.getAcceptanceState() != WorkSessionAcceptanceState.VALIDATED
                || !requireHash(projectionSha256, "validation projection")
                        .equals(session.getValidationProjectionSha256())
                || !requireRevision(definitionRevision)
                        .equals(session.getValidationDefinitionRevision())) {
            throw blocked("Integration readiness requires the exact validated projection");
        }
        Instant now = Instant.now();
        session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setAcceptanceBlockedCheck(null);
        session.setAcceptanceNextAction("Request the separately authorized commit or publish operation");
        session.setIntegrationReadyAt(now);
        session.setUpdatedAt(now);
        return snapshot(workSessionRepository.save(session));
    }

    void invalidateForNewRun(WorkSessionEntity session) {
        clearValidation(session);
        session.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
        session.setSourceTreeFingerprintSha256(null);
        session.setSourceTreeObservedAt(null);
        session.setAcceptanceNextAction(
                "Wait for Codex process completion, then observe and validate the resulting source tree");
        session.setUpdatedAt(Instant.now());
        workSessionRepository.save(session);
    }

    private WorkSessionEntity locked(Long sessionId) {
        return workSessionRepository.findLockedWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
    }

    private void requireCurrentTree(WorkSessionEntity session, String fingerprintSha256) {
        String fingerprint = requireHash(fingerprintSha256, "source tree fingerprint");
        if (!fingerprint.equals(session.getSourceTreeFingerprintSha256())
                || session.getSourceTreeObservedAt() == null) {
            throw blocked("Acceptance projection does not own the current source tree");
        }
    }

    private void clearValidation(WorkSessionEntity session) {
        session.setValidationProjectionSha256(null);
        session.setValidationDefinitionRevision(null);
        session.setAcceptanceBlockedCheck(null);
        session.setValidatedAt(null);
        session.setIntegrationReadyAt(null);
    }

    private String requireHash(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " must be an exact lowercase SHA-256");
        }
        return value;
    }

    private String requireRevision(String value) {
        return requireText(value, 80, "validation definition revision");
    }

    private String requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds its bounded length");
        }
        return normalized;
    }

    private WorkSessionOperationBlockedException blocked(String detail) {
        return new WorkSessionOperationBlockedException(detail + "; validation state was not promoted");
    }

    private WorkSessionAcceptanceSnapshot snapshot(WorkSessionEntity session) {
        return new WorkSessionAcceptanceSnapshot(
                session.getId(),
                session.getAcceptanceState(),
                session.getSourceTreeFingerprintSha256(),
                session.getSourceTreeObservedAt(),
                session.getValidationProjectionSha256(),
                session.getValidationDefinitionRevision(),
                session.getAcceptanceBlockedCheck(),
                session.getAcceptanceNextAction(),
                session.getValidatedAt(),
                session.getIntegrationReadyAt());
    }
}
