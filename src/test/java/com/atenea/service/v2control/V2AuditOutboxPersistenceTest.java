package com.atenea.service.v2control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2AuditEventEntity;
import com.atenea.persistence.v2control.V2AuditEventRepository;
import com.atenea.persistence.v2control.V2OutboxEventEntity;
import com.atenea.persistence.v2control.V2OutboxEventRepository;
import com.atenea.persistence.v2control.V2OutboxState;
import com.atenea.v2.control.V2FailureCategory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class V2AuditOutboxPersistenceTest {

    @Autowired private V2AuditOutboxService service;
    @Autowired private V2AuditEventRepository auditRepository;
    @Autowired private V2OutboxEventRepository outboxRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void persistsSanitizedAuditAndPendingOutboxInOneBoundary() {
        Fixture fixture = fixture();
        long auditsBefore = auditRepository.count();
        long outboxBefore = outboxRepository.count();
        V2AuditFact fact = fact(fixture, UUID.randomUUID());

        V2AuditOutboxResult result = service.record(fact);

        assertEquals(auditsBefore + 1, auditRepository.count());
        assertEquals(outboxBefore + 1, outboxRepository.count());
        V2AuditEventEntity audit = auditRepository.findById(result.auditEventId()).orElseThrow();
        V2OutboxEventEntity outbox = outboxRepository.findById(result.outboxEventId()).orElseThrow();
        assertEquals(fact.operationId(), audit.getOperationId());
        assertEquals(V2FailureCategory.POLICY, audit.getFailureCategory());
        assertEquals("V2_POLICY_DISABLED", audit.getFailureCode());
        assertEquals(V2OutboxState.PENDING, outbox.getState());
        assertEquals(0, outbox.getAttemptCount());
        assertEquals(audit.getId(), outbox.getAuditEvent().getId());
        assertEquals(64, outbox.getDeduplicationSha256().length());

        List<String> auditColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'v2_audit_event'
                """, String.class);
        for (String forbidden : List.of(
                "payload", "prompt", "response", "attachment", "credential",
                "token", "cookie", "environment", "codex_history")) {
            assertFalse(auditColumns.contains(forbidden));
        }
    }

    @Test
    void databaseRejectsAuditUpdate() {
        Fixture fixture = fixture();
        V2AuditOutboxResult result = service.record(fact(fixture, UUID.randomUUID()));

        assertTrue(auditRepository.existsById(result.auditEventId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "UPDATE v2_audit_event SET state = 'CHANGED' WHERE id = ?",
                result.auditEventId()));
    }

    @Test
    void databaseRejectsAuditDelete() {
        Fixture fixture = fixture();
        V2AuditOutboxResult result = service.record(fact(fixture, UUID.randomUUID()));

        assertTrue(auditRepository.existsById(result.auditEventId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "DELETE FROM v2_audit_event WHERE id = ?",
                result.auditEventId()));
    }

    private V2AuditFact fact(Fixture fixture, UUID operationId) {
        return new V2AuditFact(
                operationId,
                fixture.projectId(),
                fixture.actorId(),
                "control-contracts",
                "POLICY_DENIED",
                "DENIED",
                0,
                "a".repeat(64),
                "b".repeat(64),
                V2FailureCategory.POLICY,
                "V2_POLICY_DISABLED",
                0,
                2,
                Instant.parse("2026-08-11T20:00:00Z"));
    }

    private Fixture fixture() {
        String identity = UUID.randomUUID().toString();
        ProjectEntity project = new ProjectEntity();
        project.setName("v2-audit-" + identity);
        project.setRepoPath("/tmp/v2-audit-" + identity);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(project.getCreatedAt());
        project = projectRepository.saveAndFlush(project);

        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(identity + "@atenea.test");
        operator.setDisplayName("V2 audit fixture");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCreatedAt(Instant.now());
        operator.setUpdatedAt(operator.getCreatedAt());
        operator = operatorRepository.saveAndFlush(operator);
        return new Fixture(project.getId(), operator.getId());
    }

    private record Fixture(Long projectId, Long actorId) {
    }
}
