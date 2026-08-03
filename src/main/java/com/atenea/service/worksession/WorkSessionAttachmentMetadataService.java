package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AttachmentKind;
import com.atenea.persistence.worksession.AttachmentSource;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSessionAttachmentMetadataService {

    public static final long DEFAULT_MAX_FILE_BYTES = 16L * 1024L * 1024L;
    public static final long DEFAULT_MAX_SESSION_BYTES = 256L * 1024L * 1024L;
    public static final int MAX_LIST_SIZE = 100;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final WorkSessionAttachmentRepository attachmentRepository;

    public WorkSessionAttachmentMetadataService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            WorkSessionAttachmentRepository attachmentRepository
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.attachmentRepository = attachmentRepository;
    }

    @Transactional
    public WorkSessionAttachmentEntity index(Long workSessionId, AttachmentIndexRequest request) {
        requireRequest(request);
        Optional<WorkSessionAttachmentEntity> existing = attachmentRepository.findById(request.attachmentId());
        if (existing.isPresent()) {
            return requireIdentical(existing.orElseThrow(), workSessionId, request);
        }

        WorkSessionEntity session = workSessionRepository.findLockedWithProjectById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));
        requireRemoteWorker(session, request.workerId());

        AgentRunEntity agentRun = null;
        if (request.agentRunId() != null) {
            agentRun = agentRunRepository.findWithSessionById(request.agentRunId())
                    .orElseThrow(() -> new AttachmentOwnershipException(
                            "El AgentRun indicado no pertenece a esta WorkSession."));
            if (!Objects.equals(agentRun.getSession().getId(), session.getId())) {
                throw new AttachmentOwnershipException(
                        "El AgentRun indicado no pertenece a esta WorkSession.");
            }
        }

        long retainedBytes = attachmentRepository.sumSizeBytesByWorkSessionId(workSessionId);
        if (request.sizeBytes() > DEFAULT_MAX_SESSION_BYTES - retainedBytes) {
            throw new AttachmentLimitException(
                    "La WorkSession supera el límite retenido de 256 MiB. Elimina o reclasifica evidencia antes de subir.");
        }

        Instant indexedAt = Instant.now();
        WorkSessionAttachmentEntity attachment = new WorkSessionAttachmentEntity();
        attachment.setId(request.attachmentId());
        attachment.setWorkSession(session);
        attachment.setProject(session.getProject());
        attachment.setAgentRun(agentRun);
        attachment.setSource(request.source());
        attachment.setKind(request.kind());
        attachment.setOriginalFilename(normalizeFilename(request.originalFilename()));
        attachment.setContentType(request.contentType().trim().toLowerCase());
        attachment.setSizeBytes(request.sizeBytes());
        attachment.setRetentionClass(request.retentionClass());
        attachment.setCreatedAt(request.createdAt());
        attachment.setRetainUntil(request.createdAt().plus(request.retentionClass().duration()));
        attachment.setSha256(request.sha256());
        attachment.setWorkerId(request.workerId());
        attachment.setStorageIdentity(request.storageIdentity());
        attachment.setIndexedAt(indexedAt);
        return attachmentRepository.save(attachment);
    }

    @Transactional(readOnly = true)
    public WorkSessionAttachmentEntity get(Long workSessionId, UUID attachmentId) {
        requireSession(workSessionId);
        return attachmentRepository.findByIdAndWorkSessionId(attachmentId, workSessionId)
                .orElseThrow(() -> new AttachmentNotFoundException(
                        "El adjunto no existe en esta WorkSession."));
    }

    @Transactional(readOnly = true)
    public List<WorkSessionAttachmentEntity> list(Long workSessionId, int limit) {
        requireSession(workSessionId);
        return attachmentRepository.findByWorkSessionIdOrderByCreatedAtDescIdDesc(
                workSessionId,
                PageRequest.of(0, boundedLimit(limit)));
    }

    @Transactional(readOnly = true)
    public List<WorkSessionAttachmentEntity> screenshots(
            Long workSessionId,
            AttachmentSource source,
            int offset,
            int limit
    ) {
        requireSession(workSessionId);
        if (offset < 0) {
            throw new IllegalArgumentException("El desplazamiento no puede ser negativo.");
        }
        int size = boundedLimit(limit);
        PageRequest page = PageRequest.of(offset / size, size);
        if (offset % size != 0) {
            int fetchSize = Math.min(MAX_LIST_SIZE, offset + size);
            List<WorkSessionAttachmentEntity> fetched = source == null
                    ? attachmentRepository.findByWorkSessionIdAndKindOrderByCreatedAtDescIdDesc(
                            workSessionId, AttachmentKind.IMAGE, PageRequest.of(0, fetchSize))
                    : attachmentRepository.findByWorkSessionIdAndKindAndSourceOrderByCreatedAtDescIdDesc(
                            workSessionId, AttachmentKind.IMAGE, source, PageRequest.of(0, fetchSize));
            return fetched.stream().skip(offset).limit(size).toList();
        }
        return source == null
                ? attachmentRepository.findByWorkSessionIdAndKindOrderByCreatedAtDescIdDesc(
                        workSessionId, AttachmentKind.IMAGE, page)
                : attachmentRepository.findByWorkSessionIdAndKindAndSourceOrderByCreatedAtDescIdDesc(
                        workSessionId, AttachmentKind.IMAGE, source, page);
    }

    @Transactional(readOnly = true)
    public Optional<WorkSessionAttachmentEntity> latestScreenshot(
            Long workSessionId,
            AttachmentSource source
    ) {
        return screenshots(workSessionId, source, 0, 1).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<WorkSessionAttachmentEntity> previousScreenshot(
            Long workSessionId,
            AttachmentSource source
    ) {
        return screenshots(workSessionId, source, 1, 1).stream().findFirst();
    }

    private void requireRequest(AttachmentIndexRequest request) {
        if (request == null || request.attachmentId() == null) {
            throw new IllegalArgumentException("Falta la identidad inmutable del adjunto.");
        }
        if (request.source() == null || request.kind() == null || request.retentionClass() == null) {
            throw new IllegalArgumentException("Faltan source, kind o retentionClass del adjunto.");
        }
        if (request.sizeBytes() <= 0 || request.sizeBytes() > DEFAULT_MAX_FILE_BYTES) {
            throw new AttachmentLimitException("Cada adjunto debe medir entre 1 byte y 16 MiB.");
        }
        if (request.contentType() == null || request.contentType().isBlank()) {
            throw new IllegalArgumentException("Falta el tipo de contenido validado.");
        }
        if (request.sha256() == null || !SHA256.matcher(request.sha256()).matches()) {
            throw new IllegalArgumentException("La identidad SHA-256 del adjunto no es válida.");
        }
        if (request.workerId() == null || request.workerId().isBlank()
                || request.storageIdentity() == null || request.storageIdentity().isBlank()) {
            throw new IllegalArgumentException("Falta la identidad opaca de almacenamiento.");
        }
        if (request.createdAt() == null || request.createdAt().isAfter(Instant.now().plusSeconds(60))) {
            throw new IllegalArgumentException("La fecha de creación del adjunto no es válida.");
        }
        if (request.source() == AttachmentSource.BROWSER_SCREENSHOT && request.kind() != AttachmentKind.IMAGE
                || request.source() == AttachmentSource.BROWSER_TRACE && request.kind() != AttachmentKind.TRACE
                || request.source() == AttachmentSource.REPORT && request.kind() != AttachmentKind.REPORT) {
            throw new IllegalArgumentException("El source y kind del adjunto no son compatibles.");
        }
    }

    private WorkSessionAttachmentEntity requireIdentical(
            WorkSessionAttachmentEntity existing,
            Long workSessionId,
            AttachmentIndexRequest request
    ) {
        boolean identical = Objects.equals(existing.getWorkSession().getId(), workSessionId)
                && Objects.equals(id(existing.getAgentRun()), request.agentRunId())
                && existing.getSource() == request.source()
                && existing.getKind() == request.kind()
                && Objects.equals(existing.getOriginalFilename(), normalizeFilename(request.originalFilename()))
                && Objects.equals(existing.getContentType(), request.contentType().trim().toLowerCase())
                && existing.getSizeBytes() == request.sizeBytes()
                && existing.getRetentionClass() == request.retentionClass()
                && Objects.equals(existing.getSha256(), request.sha256())
                && Objects.equals(existing.getWorkerId(), request.workerId())
                && Objects.equals(existing.getStorageIdentity(), request.storageIdentity())
                && Objects.equals(existing.getCreatedAt(), request.createdAt());
        if (!identical) {
            throw new AttachmentConflictException(
                    "La identidad del adjunto ya existe con otro contenido u ownership.");
        }
        return existing;
    }

    private void requireRemoteWorker(WorkSessionEntity session, String workerId) {
        if (session.getExecutionTarget() != ExecutionTarget.REMOTE
                || !Objects.equals(session.getSelectedWorkerId(), workerId)) {
            throw new AttachmentOwnershipException(
                    "La WorkSession no está vinculada al worker indicado.");
        }
    }

    private void requireSession(Long workSessionId) {
        if (!workSessionRepository.existsById(workSessionId)) {
            throw new WorkSessionNotFoundException(workSessionId);
        }
    }

    private int boundedLimit(int limit) {
        if (limit < 1 || limit > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("El límite debe estar entre 1 y 100.");
        }
        return limit;
    }

    private String normalizeFilename(String filename) {
        String value = filename == null ? "" : filename.trim();
        if (value.isBlank()) {
            value = "attachment.bin";
        }
        value = value.replace('\\', '/');
        int lastSlash = value.lastIndexOf('/');
        if (lastSlash >= 0) {
            value = value.substring(lastSlash + 1);
        }
        value = value.replaceAll("[\\p{Cntrl}]", "_");
        if (value.length() > 180) {
            value = value.substring(value.length() - 180);
        }
        return value;
    }

    private Long id(AgentRunEntity run) {
        return run == null ? null : run.getId();
    }
}
