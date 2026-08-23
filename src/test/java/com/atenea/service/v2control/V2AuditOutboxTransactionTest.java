package com.atenea.service.v2control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2AuditEventRepository;
import com.atenea.persistence.v2control.V2OutboxEventRepository;
import com.atenea.v2.control.V2FailureCategory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class V2AuditOutboxTransactionTest {

    @Autowired private V2AuditOutboxService service;
    @Autowired private V2AuditEventRepository auditRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @MockBean private V2OutboxEventRepository outboxRepository;

    @Test
    void rollsBackAuditWhenOutboxPersistenceFails() {
        String identity = UUID.randomUUID().toString();
        ProjectEntity project = new ProjectEntity();
        project.setName("v2-rollback-" + identity);
        project.setRepoPath("/tmp/v2-rollback-" + identity);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(project.getCreatedAt());
        project = projectRepository.saveAndFlush(project);

        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(identity + "@atenea.test");
        operator.setDisplayName("V2 rollback fixture");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCreatedAt(Instant.now());
        operator.setUpdatedAt(operator.getCreatedAt());
        operator = operatorRepository.saveAndFlush(operator);

        long auditsBefore = auditRepository.count();
        when(outboxRepository.saveAndFlush(any()))
                .thenThrow(new IllegalStateException("synthetic outbox failure"));
        V2AuditFact fact = new V2AuditFact(
                UUID.randomUUID(),
                project.getId(),
                operator.getId(),
                "control-contracts",
                "POLICY_DENIED",
                "DENIED",
                0,
                "c".repeat(64),
                "d".repeat(64),
                V2FailureCategory.POLICY,
                "V2_POLICY_DISABLED",
                0,
                1,
                Instant.parse("2026-08-11T20:01:00Z"));

        assertThrows(IllegalStateException.class, () -> service.record(fact));
        assertEquals(auditsBefore, auditRepository.count());
    }
}
