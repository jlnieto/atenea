package com.atenea.service.v2control;

import com.atenea.persistence.v2control.V2AuditEventEntity;
import com.atenea.persistence.v2control.V2AuditEventRepository;
import com.atenea.persistence.v2control.V2OutboxEventEntity;
import com.atenea.persistence.v2control.V2OutboxEventRepository;
import com.atenea.persistence.v2control.V2OutboxState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class V2AuditOutboxService {

    private final V2AuditEventRepository auditRepository;
    private final V2OutboxEventRepository outboxRepository;

    public V2AuditOutboxService(
            V2AuditEventRepository auditRepository,
            V2OutboxEventRepository outboxRepository) {
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public V2AuditOutboxResult record(V2AuditFact fact) {
        V2AuditEventEntity audit = new V2AuditEventEntity();
        audit.setId(UUID.randomUUID());
        audit.setOperationId(fact.operationId());
        audit.setProjectId(fact.projectId());
        audit.setActorId(fact.actorId());
        audit.setCapability(fact.capability());
        audit.setEventType(fact.eventType());
        audit.setState(fact.state());
        audit.setRevision(fact.revision());
        audit.setRequestFingerprintSha256(fact.requestFingerprintSha256());
        audit.setTargetFingerprintSha256(fact.targetFingerprintSha256());
        audit.setFailureCategory(fact.failureCategory());
        audit.setFailureCode(fact.failureCode());
        audit.setItemCount(fact.itemCount());
        audit.setDurationMillis(fact.durationMillis());
        audit.setOccurredAt(fact.occurredAt());
        audit = auditRepository.saveAndFlush(audit);

        V2OutboxEventEntity outbox = new V2OutboxEventEntity();
        outbox.setId(UUID.randomUUID());
        outbox.setAuditEvent(audit);
        outbox.setOperationId(fact.operationId());
        outbox.setCapability(fact.capability());
        outbox.setEventType(fact.eventType());
        outbox.setRevision(fact.revision());
        outbox.setDeduplicationSha256(deduplicationDigest(fact));
        outbox.setState(V2OutboxState.PENDING);
        outbox.setAttemptCount(0);
        outbox.setCreatedAt(fact.occurredAt());
        outbox.setUpdatedAt(fact.occurredAt());
        outbox = outboxRepository.saveAndFlush(outbox);
        return new V2AuditOutboxResult(audit.getId(), outbox.getId());
    }

    private static String deduplicationDigest(V2AuditFact fact) {
        String canonical = fact.operationId() + "\n" + fact.revision() + "\n" + fact.eventType();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
