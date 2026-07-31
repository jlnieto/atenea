package com.atenea.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.notification.NotificationCategory;
import com.atenea.persistence.notification.NotificationDeliveryEntity;
import com.atenea.persistence.notification.NotificationDeliveryRepository;
import com.atenea.persistence.notification.NotificationEventRepository;
import com.atenea.persistence.notification.NotificationPreferenceEntity;
import com.atenea.persistence.notification.NotificationPreferenceRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NotificationOutboxPersistenceTest {

    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @Autowired private NotificationOutboxService service;
    @Autowired private NotificationDeliveryClaimService claimService;
    @Autowired private NotificationEventRepository eventRepository;
    @Autowired private NotificationDeliveryRepository deliveryRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private OperatorPushDeviceRepository deviceRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private SessionTurnRepository turnRepository;
    @Autowired private WorkSessionRepository sessionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBefore() { clean(); }

    @AfterEach
    void cleanAfter() { clean(); }

    @Test
    void createsOneEventAndOneDeliveryPerEnabledActiveDeviceIdempotently() {
        Fixture fixture = fixture(AgentRunStatus.SUCCEEDED);
        OperatorPushDeviceEntity enabled = device(fixture.operator(), true);
        OperatorPushDeviceEntity disabled = device(fixture.operator(), true);
        device(fixture.operator(), false);
        preference(disabled, NotificationCategory.RUN_COMPLETED, false);

        NotificationOutboxResult first = service.record(
                fixture.run().getId(), NotificationCategory.RUN_COMPLETED, 7);
        NotificationOutboxResult repeated = service.record(
                fixture.run().getId(), NotificationCategory.RUN_COMPLETED, 7);

        assertTrue(first.created());
        assertFalse(repeated.created());
        assertEquals(first.event().getId(), repeated.event().getId());
        assertEquals(1, first.deliveryCount());
        List<NotificationDeliveryEntity> deliveries =
                deliveryRepository.findByEventIdOrderByDeviceIdAsc(first.event().getId());
        assertEquals(1, deliveries.size());
        assertEquals(enabled.getId(), deliveries.getFirst().getDevice().getId());
        assertEquals("FCM", deliveries.getFirst().getChannel());
        assertEquals(0, deliveries.getFirst().getAttemptCount());
    }

    @Test
    void claimsAndCompletesOnePersistedDeliveryWithoutConversationContent() {
        Fixture fixture = fixture(AgentRunStatus.SUCCEEDED);
        device(fixture.operator(), true);
        NotificationOutboxResult result = service.record(
                fixture.run().getId(), NotificationCategory.RUN_COMPLETED, 7);
        NotificationDeliveryEntity pending = deliveryRepository
                .findByEventIdOrderByDeviceIdAsc(result.event().getId()).getFirst();

        NotificationDeliveryCommand command = claimService.claim(pending.getId());

        assertEquals("RUN_COMPLETED", command.data().get("type"));
        assertEquals(fixture.session().getId(), command.data().get("sessionId"));
        assertEquals(fixture.run().getId(), command.data().get("runId"));
        assertFalse(command.data().toString().contains(fixture.turn().getMessageText()));
        NotificationDeliveryEntity sending = deliveryRepository.findById(pending.getId()).orElseThrow();
        assertEquals(com.atenea.persistence.notification.NotificationDeliveryState.SENDING, sending.getState());
        assertEquals(1, sending.getAttemptCount());

        claimService.delivered(pending.getId());

        NotificationDeliveryEntity delivered = deliveryRepository.findById(pending.getId()).orElseThrow();
        assertEquals(com.atenea.persistence.notification.NotificationDeliveryState.DELIVERED, delivered.getState());
        assertNull(delivered.getDiagnosticCode());
        assertTrue(delivered.getDeliveredAt() != null);
    }

    @Test
    void missingPreferenceDefaultsAllThreeCategoriesToEnabled() {
        Fixture failed = fixture(AgentRunStatus.FAILED);
        device(failed.operator(), true);

        NotificationOutboxResult result = service.record(
                failed.run().getId(), NotificationCategory.RUN_FAILED, 3);

        assertEquals(1, result.deliveryCount());
        assertEquals("La tarea necesita atención", result.event().getSafeTitle());
        assertEquals("agent-run-safe-v1", result.event().getTemplateVersion());
        assertEquals("WORK_SESSION_CONVERSATION", result.event().getDeepLinkKind());
        assertEquals(64, result.event().getDeduplicationSha256().length());
    }

    @Test
    void actionRequiredIsSafeForNonTerminalRunAndContainsNoConversationContent() {
        Fixture running = fixture(AgentRunStatus.RUNNING);
        device(running.operator(), true);

        NotificationOutboxResult result = service.record(
                running.run().getId(), NotificationCategory.ACTION_REQUIRED, 11);

        assertEquals("Se necesita una acción", result.event().getSafeTitle());
        assertEquals("Abre Atenea para continuar esta sesión", result.event().getSafeBody());
        assertFalse(result.event().getSafeBody().contains(running.turn().getMessageText()));
    }

    @Test
    void rejectsCategoryThatDoesNotMatchTerminalOutcome() {
        Fixture failed = fixture(AgentRunStatus.FAILED);

        assertThrows(IllegalStateException.class, () -> service.record(
                failed.run().getId(), NotificationCategory.RUN_COMPLETED, 1));
        assertEquals(0, eventRepository.count());
        assertEquals(0, deliveryRepository.count());
    }

    @Test
    void databaseRejectsFreeFormPayloadCopy() {
        Fixture fixture = fixture(AgentRunStatus.SUCCEEDED);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO notification_event (
                    id, deduplication_sha256, category, template_version,
                    deep_link_kind, session_id, agent_run_id, source_revision,
                    safe_title, safe_body, created_at
                ) VALUES (?, ?, 'RUN_COMPLETED', 'agent-run-safe-v1',
                    'WORK_SESSION_CONVERSATION', ?, ?, 1,
                    'Tarea completada', ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(), "a".repeat(64), fixture.session().getId(),
                fixture.run().getId(), "prompt or answer content"));
        assertEquals(0, eventRepository.count());
    }

    @Test
    void sourceRevisionCreatesDistinctEventsButNeverDuplicateOwnership() {
        Fixture running = fixture(AgentRunStatus.RUNNING);
        device(running.operator(), true);

        NotificationOutboxResult first = service.record(
                running.run().getId(), NotificationCategory.ACTION_REQUIRED, 1);
        NotificationOutboxResult second = service.record(
                running.run().getId(), NotificationCategory.ACTION_REQUIRED, 2);

        assertEquals(2, eventRepository.count());
        assertEquals(2, deliveryRepository.count());
        assertFalse(first.event().getId().equals(second.event().getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO notification_delivery (
                    event_id, device_id, channel, state, attempt_count,
                    expires_at, created_at, updated_at
                ) SELECT event_id, device_id, channel, state, attempt_count,
                    expires_at, created_at, updated_at
                  FROM notification_delivery WHERE event_id = ?
                """, first.event().getId()));
    }

    private Fixture fixture(AgentRunStatus status) {
        long value = FIXTURE_SEQUENCE.incrementAndGet();
        Instant now = Instant.parse("2026-07-31T10:00:00Z");
        OperatorEntity operator = new OperatorEntity();
        operator.setEmail("outbox-" + value + "@atenea.test");
        operator.setDisplayName("Outbox operator " + value);
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        operator = operatorRepository.save(operator);

        ProjectEntity project = new ProjectEntity();
        project.setName("outbox-project-" + value);
        project.setRepoPath("/workspace/repos/internal/outbox-" + value);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project = projectRepository.save(project);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Outbox persistence " + value);
        session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:outbox:" + value);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now); session.setLastActivityAt(now);
        session.setCreatedAt(now); session.setUpdatedAt(now);
        session = sessionRepository.save(session);

        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session); turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText("Synthetic prompt that must not enter notification payloads");
        turn.setCreatedAt(now); turn = turnRepository.save(turn);

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session); run.setOriginTurn(turn); run.setStatus(status);
        run.setTargetRepoPath(project.getRepoPath()); run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity()); run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setStartedAt(now); run.setCreatedAt(now); run.setLifecycleRevision(1);
        if (status.isTerminal()) run.setFinishedAt(now.plusSeconds(30));
        run = agentRunRepository.saveAndFlush(run);
        return new Fixture(operator, session, turn, run);
    }

    private OperatorPushDeviceEntity device(OperatorEntity operator, boolean active) {
        long value = FIXTURE_SEQUENCE.incrementAndGet();
        Instant now = Instant.parse("2026-07-31T10:00:00Z");
        OperatorPushDeviceEntity device = new OperatorPushDeviceEntity();
        device.setOperator(operator);
        device.setPushToken("synthetic-device-" + value);
        device.setDeviceId("device-" + value);
        device.setDeviceName("Test device");
        device.setPlatform("ANDROID");
        device.setAppVersion("test");
        device.setActive(active);
        device.setLastRegisteredAt(now); device.setCreatedAt(now); device.setUpdatedAt(now);
        return deviceRepository.save(device);
    }

    private void preference(
            OperatorPushDeviceEntity device,
            NotificationCategory category,
            boolean enabled) {
        Instant now = Instant.parse("2026-07-31T10:00:00Z");
        NotificationPreferenceEntity preference = new NotificationPreferenceEntity();
        preference.setDevice(device); preference.setCategory(category); preference.setEnabled(enabled);
        preference.setCreatedAt(now); preference.setUpdatedAt(now);
        preferenceRepository.save(preference);
    }

    private void clean() {
        deliveryRepository.deleteAll(); eventRepository.deleteAll(); preferenceRepository.deleteAll();
        deviceRepository.deleteAll();
        jdbcTemplate.update("UPDATE agent_run SET retry_of_run_id = NULL WHERE retry_of_run_id IS NOT NULL");
        agentRunRepository.deleteAll(); turnRepository.deleteAll(); sessionRepository.deleteAll();
        projectRepository.deleteAll(); operatorRepository.deleteAll();
    }

    private record Fixture(
            OperatorEntity operator,
            WorkSessionEntity session,
            SessionTurnEntity turn,
            AgentRunEntity run) {
    }
}
