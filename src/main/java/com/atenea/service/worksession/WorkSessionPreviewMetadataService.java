package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.PreviewState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewEntity;
import com.atenea.persistence.worksession.WorkSessionPreviewRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionPreviewMetadataService {

    public static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    public static final Duration HARD_LIFETIME = Duration.ofHours(8);
    public static final Duration AUDIT_RETENTION = Duration.ofDays(30);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_ALLOCATION_IDENTITY = 200;
    private static final int MAX_FAILURE_CODE = 80;
    private static final int MAX_OPERATOR_TEXT = 500;

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final WorkSessionPreviewRepository previewRepository;

    public WorkSessionPreviewMetadataService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            WorkSessionPreviewRepository previewRepository
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.previewRepository = previewRepository;
    }

    @Transactional
    public WorkSessionPreviewEntity create(Long workSessionId, PreviewIndexRequest request) {
        requireCreateRequest(request);
        WorkSessionPreviewEntity existing = previewRepository.findById(request.previewId()).orElse(null);
        if (existing != null) {
            return requireIdentical(existing, workSessionId, request);
        }

        WorkSessionEntity session = workSessionRepository.findLockedWithProjectById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));
        requireRemoteWorker(session, request.workerId());
        if (previewRepository.existsByWorkSessionIdAndStateIn(
                workSessionId, PreviewState.reconcilableStates())) {
            throw new PreviewConflictException(
                    "La WorkSession ya tiene un preview activo o en reconciliación.");
        }

        AgentRunEntity agentRun = resolveAgentRun(session, request.agentRunId());
        Instant now = request.createdAt();
        WorkSessionPreviewEntity preview = new WorkSessionPreviewEntity();
        preview.setId(request.previewId());
        preview.setWorkSession(session);
        preview.setProject(session.getProject());
        preview.setAgentRun(agentRun);
        preview.setWorkerId(request.workerId().trim());
        preview.setAllocationIdentity(request.allocationIdentity().trim());
        preview.setAllocationFingerprint(request.allocationFingerprint());
        preview.setState(PreviewState.STARTING);
        preview.setLifecycleRevision(1L);
        preview.setLocalhostCompatible(request.localhostCompatible());
        preview.setLeaseExpiresAt(now.plus(LEASE_DURATION));
        preview.setHardExpiresAt(now.plus(HARD_LIFETIME));
        preview.setAuditRetainUntil(now.plus(AUDIT_RETENTION));
        preview.setNextAction("Espera a que el runtime y la ruta privada estén listos.");
        preview.setCreatedAt(now);
        preview.setUpdatedAt(now);
        return previewRepository.save(preview);
    }

    @Transactional(readOnly = true)
    public WorkSessionPreviewEntity latest(Long workSessionId) {
        requireSession(workSessionId);
        return previewRepository.findFirstByWorkSessionIdOrderByCreatedAtDescIdDesc(workSessionId)
                .orElseThrow(() -> new PreviewNotFoundException(null));
    }

    @Transactional(readOnly = true)
    public WorkSessionPreviewEntity get(Long workSessionId, UUID previewId) {
        requireSession(workSessionId);
        return previewRepository.findByIdAndWorkSessionId(previewId, workSessionId)
                .orElseThrow(() -> new PreviewNotFoundException(previewId));
    }

    @Transactional(readOnly = true)
    public List<WorkSessionPreviewEntity> reconcilable() {
        return previewRepository.findByStateInOrderByCreatedAtAscIdAsc(
                PreviewState.reconcilableStates());
    }

    @Transactional(readOnly = true)
    public List<WorkSessionPreviewEntity> retainedAudit(Long workSessionId, Instant now) {
        requireSession(workSessionId);
        return previewRepository
                .findByWorkSessionIdAndAuditRetainUntilAfterOrderByCreatedAtDescIdDesc(
                        workSessionId, requireNow(now));
    }

    @Transactional
    public WorkSessionPreviewEntity markReady(
            UUID previewId,
            long expectedRevision,
            String privateUrl,
            Instant now
    ) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        requireState(preview, PreviewState.STARTING, PreviewState.RECONCILING);
        Instant timestamp = requireBeforeHardLimit(preview, now);
        preview.setState(PreviewState.READY);
        preview.setPrivateUrl(requirePrivateUrl(privateUrl));
        preview.setFailureCode(null);
        preview.setFailureReason(null);
        preview.setNextAction("Abre el preview privado.");
        preview.setReadyAt(preview.getReadyAt() == null ? timestamp : preview.getReadyAt());
        preview.setLeaseExpiresAt(boundedLease(preview, timestamp));
        preview.setUpdatedAt(timestamp);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity markReady(
            UUID previewId,
            long expectedRevision,
            String privateUrl,
            boolean localhostCompatible,
            Instant leaseExpiresAt,
            Instant hardExpiresAt,
            Instant now
    ) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        requireState(preview, PreviewState.STARTING, PreviewState.RECONCILING);
        Instant timestamp = requireBeforeHardLimit(preview, now);
        if (leaseExpiresAt == null
                || hardExpiresAt == null
                || !leaseExpiresAt.isAfter(timestamp)
                || leaseExpiresAt.isAfter(hardExpiresAt)
                || hardExpiresAt.isAfter(preview.getHardExpiresAt().plusSeconds(5))) {
            throw new PreviewOwnershipException(
                    "El lease devuelto por el worker no coincide con el límite persistido.");
        }
        preview.setState(PreviewState.READY);
        preview.setPrivateUrl(requirePrivateUrl(privateUrl));
        preview.setLocalhostCompatible(localhostCompatible);
        preview.setLeaseExpiresAt(leaseExpiresAt);
        preview.setHardExpiresAt(hardExpiresAt);
        preview.setFailureCode(null);
        preview.setFailureReason(null);
        preview.setNextAction("Abre el preview privado.");
        preview.setReadyAt(preview.getReadyAt() == null ? timestamp : preview.getReadyAt());
        preview.setUpdatedAt(timestamp);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity markReconciling(
            UUID previewId,
            long expectedRevision,
            Instant now
    ) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        requireState(preview, PreviewState.STARTING, PreviewState.READY, PreviewState.RECONCILING);
        Instant timestamp = requireBeforeHardLimit(preview, now);
        preview.setState(PreviewState.RECONCILING);
        preview.setPrivateUrl(null);
        preview.setFailureCode(null);
        preview.setFailureReason(null);
        preview.setNextAction("Espera a que Atenea confirme la misma ruta privada.");
        preview.setUpdatedAt(timestamp);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity renew(UUID previewId, long expectedRevision, Instant now) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        requireState(preview, PreviewState.READY);
        Instant timestamp = requireBeforeHardLimit(preview, now);
        preview.setLeaseExpiresAt(boundedLease(preview, timestamp));
        preview.setUpdatedAt(timestamp);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity renewFromWorker(
            UUID previewId,
            long expectedRevision,
            Instant leaseExpiresAt,
            Instant hardExpiresAt,
            Instant now
    ) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        requireState(preview, PreviewState.READY);
        Instant timestamp = requireBeforeHardLimit(preview, now);
        if (leaseExpiresAt == null
                || hardExpiresAt == null
                || !leaseExpiresAt.isAfter(timestamp)
                || leaseExpiresAt.isAfter(hardExpiresAt)
                || !hardExpiresAt.equals(preview.getHardExpiresAt())) {
            throw new PreviewOwnershipException(
                    "La renovación devuelta por el worker no coincide con el límite persistido.");
        }
        preview.setLeaseExpiresAt(leaseExpiresAt);
        preview.setUpdatedAt(timestamp);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity block(
            UUID previewId,
            long expectedRevision,
            String failureCode,
            String failureReason,
            String nextAction,
            Instant now
    ) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        requireState(preview, PreviewState.STARTING, PreviewState.READY, PreviewState.RECONCILING);
        preview.setState(PreviewState.BLOCKED);
        preview.setPrivateUrl(null);
        preview.setFailureCode(sanitizeRequired(failureCode, MAX_FAILURE_CODE, "preview_blocked"));
        preview.setFailureReason(sanitizeRequired(
                failureReason, MAX_OPERATOR_TEXT, "El preview no ha podido validarse."));
        preview.setNextAction(sanitizeRequired(
                nextAction, MAX_OPERATOR_TEXT, "Revisa el runtime y vuelve a iniciar el preview."));
        preview.setStoppedAt(requireNow(now));
        preview.setUpdatedAt(now);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity stop(UUID previewId, long expectedRevision, Instant now) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        if (preview.getState() == PreviewState.STOPPED) {
            return preview;
        }
        if (preview.getState() == PreviewState.EXPIRED) {
            throw new PreviewConflictException(
                    "El preview ya expiró y no puede convertirse en detenido.");
        }
        preview.setState(PreviewState.STOPPED);
        preview.setPrivateUrl(null);
        preview.setFailureCode(null);
        preview.setFailureReason(null);
        preview.setNextAction("Inicia de nuevo el preview cuando lo necesites.");
        preview.setStoppedAt(requireNow(now));
        preview.setUpdatedAt(now);
        return previewRepository.saveAndFlush(preview);
    }

    @Transactional
    public WorkSessionPreviewEntity expire(UUID previewId, long expectedRevision, Instant now) {
        WorkSessionPreviewEntity preview = locked(previewId, expectedRevision);
        Instant timestamp = requireNow(now);
        requireState(preview, PreviewState.STARTING, PreviewState.READY, PreviewState.RECONCILING);
        if (timestamp.isBefore(preview.getLeaseExpiresAt())
                && timestamp.isBefore(preview.getHardExpiresAt())) {
            throw new PreviewConflictException("El lease del preview todavía no ha expirado.");
        }
        preview.setState(PreviewState.EXPIRED);
        preview.setPrivateUrl(null);
        preview.setFailureCode(null);
        preview.setFailureReason(null);
        preview.setNextAction("Inicia de nuevo el preview si todavía lo necesitas.");
        preview.setStoppedAt(timestamp);
        preview.setUpdatedAt(timestamp);
        return previewRepository.saveAndFlush(preview);
    }

    private WorkSessionPreviewEntity locked(UUID previewId, long expectedRevision) {
        WorkSessionPreviewEntity preview = previewRepository.findLockedById(previewId)
                .orElseThrow(() -> new PreviewNotFoundException(previewId));
        if (expectedRevision < 1 || preview.getLifecycleRevision() != expectedRevision) {
            throw new PreviewConflictException(
                    "El preview cambió mientras se procesaba la operación. Actualiza el estado y reintenta.");
        }
        return preview;
    }

    private AgentRunEntity resolveAgentRun(WorkSessionEntity session, Long agentRunId) {
        if (agentRunId == null) {
            return null;
        }
        AgentRunEntity run = agentRunRepository.findWithSessionById(agentRunId)
                .orElseThrow(() -> new PreviewOwnershipException(
                        "El AgentRun indicado no pertenece a esta WorkSession."));
        if (!Objects.equals(run.getSession().getId(), session.getId())) {
            throw new PreviewOwnershipException(
                    "El AgentRun indicado no pertenece a esta WorkSession.");
        }
        return run;
    }

    private WorkSessionPreviewEntity requireIdentical(
            WorkSessionPreviewEntity existing,
            Long workSessionId,
            PreviewIndexRequest request
    ) {
        boolean identical = Objects.equals(existing.getWorkSession().getId(), workSessionId)
                && Objects.equals(existing.getProject().getId(), existing.getWorkSession().getProject().getId())
                && Objects.equals(id(existing.getAgentRun()), request.agentRunId())
                && Objects.equals(existing.getWorkerId(), request.workerId().trim())
                && Objects.equals(existing.getAllocationIdentity(), request.allocationIdentity().trim())
                && Objects.equals(existing.getAllocationFingerprint(), request.allocationFingerprint());
        if (!identical) {
            throw new PreviewConflictException(
                    "La identidad del preview ya existe con otro ownership o allocation.");
        }
        return existing;
    }

    private void requireCreateRequest(PreviewIndexRequest request) {
        if (request == null || request.previewId() == null) {
            throw new IllegalArgumentException("Falta la identidad inmutable del preview.");
        }
        if (request.workerId() == null || request.workerId().isBlank()
                || request.workerId().length() > 80) {
            throw new IllegalArgumentException("La identidad del worker no es válida.");
        }
        if (request.allocationIdentity() == null || request.allocationIdentity().isBlank()
                || request.allocationIdentity().length() > MAX_ALLOCATION_IDENTITY) {
            throw new IllegalArgumentException("La identidad de allocation no es válida.");
        }
        if (request.allocationFingerprint() == null
                || !SHA256.matcher(request.allocationFingerprint()).matches()) {
            throw new IllegalArgumentException("El fingerprint de allocation no es válido.");
        }
        Instant createdAt = requireNow(request.createdAt());
        if (createdAt.isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("La fecha de creación del preview no es válida.");
        }
    }

    private void requireRemoteWorker(WorkSessionEntity session, String workerId) {
        if (session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !Objects.equals(session.getSelectedWorkerId(), workerId.trim())) {
            throw new PreviewOwnershipException(
                    "La WorkSession no está vinculada al worker indicado.");
        }
    }

    private void requireSession(Long workSessionId) {
        if (!workSessionRepository.existsById(workSessionId)) {
            throw new WorkSessionNotFoundException(workSessionId);
        }
    }

    private void requireState(WorkSessionPreviewEntity preview, PreviewState... allowed) {
        for (PreviewState state : allowed) {
            if (preview.getState() == state) {
                return;
            }
        }
        throw new PreviewConflictException(
                "La transición del preview no está permitida desde " + preview.getState() + ".");
    }

    private Instant requireBeforeHardLimit(WorkSessionPreviewEntity preview, Instant now) {
        Instant timestamp = requireNow(now);
        if (!timestamp.isBefore(preview.getHardExpiresAt())) {
            throw new PreviewConflictException(
                    "El preview alcanzó su límite de ocho horas. Inicia una nueva activación.");
        }
        return timestamp;
    }

    private Instant boundedLease(WorkSessionPreviewEntity preview, Instant now) {
        Instant renewed = now.plus(LEASE_DURATION);
        return renewed.isAfter(preview.getHardExpiresAt())
                ? preview.getHardExpiresAt()
                : renewed;
    }

    private String requirePrivateUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"http".equals(uri.getScheme())
                    || uri.getHost() == null
                    || !isTailnetIpv4(uri.getHost())
                    || uri.getPort() < 19000
                    || uri.getPort() > 19031
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw new PreviewOwnershipException(
                    "La ruta privada devuelta por el worker no pertenece al rango aprobado.");
        }
    }

    private boolean isTailnetIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return false;
                }
            }
            return first == 100 && second >= 64 && second <= 127;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String sanitizeRequired(String value, int maxLength, String fallback) {
        String sanitized = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isBlank()) {
            sanitized = fallback;
        }
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private Instant requireNow(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("Falta la fecha de la operación.");
        }
        return now;
    }

    private Long id(AgentRunEntity run) {
        return run == null ? null : run.getId();
    }
}
