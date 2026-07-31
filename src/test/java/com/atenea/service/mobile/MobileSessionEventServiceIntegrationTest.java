package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.api.mobile.MobileSessionEventResponse;
import com.atenea.api.mobile.MobileSessionEventsResponse;
import com.atenea.codexoperations.CodexSessionOperationsProperties;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunProgressCategory;
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
import com.atenea.service.worksession.AgentRunProgressService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.codex-session-operations.progress-enabled=true"
})
class MobileSessionEventServiceIntegrationTest {

    @Autowired private MobileSessionEventService eventService;
    @Autowired private AgentRunProgressService progressService;
    @Autowired private CodexSessionOperationsProperties properties;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkSessionRepository workSessionRepository;
    @Autowired private SessionTurnRepository sessionTurnRepository;
    @Autowired private AgentRunRepository agentRunRepository;

    @Test
    void publishesCommittedSafeProgressOnceWithoutDuplicatingTerminalConversationOutput() {
        Fixture fixture = createRunningRun();
        progressService.append(fixture.run().getId(), AgentRunProgressCategory.ACCEPTED);
        progressService.append(fixture.run().getId(), AgentRunProgressCategory.CHECKING);
        completeRun(fixture.run().getId());
        progressService.append(fixture.run().getId(), AgentRunProgressCategory.COMPLETED);

        MobileSessionEventsResponse response = eventService.getEvents(
                fixture.session().getId(), null, 200);

        List<MobileSessionEventResponse> progress = response.events().stream()
                .filter(event -> event.type().startsWith("RUN_PROGRESS_"))
                .toList();
        assertEquals(3, progress.size());
        assertEquals(3, progress.stream().map(MobileSessionEventResponse::eventId).distinct().count());
        assertEquals(List.of(3L, 2L, 1L), progress.stream()
                .map(MobileSessionEventResponse::progressSequence)
                .toList());
        assertTrue(progress.stream().allMatch(event -> event.details() == null));
        assertTrue(progress.stream().allMatch(event -> event.eventId().startsWith(
                "progress:" + fixture.run().getId() + ":")));

        assertEquals(1, response.events().stream()
                .filter(event -> event.type().equals("RUN_PROGRESS_COMPLETED"))
                .count());
        assertFalse(response.events().stream()
                .anyMatch(event -> event.type().equals("RUN_SUCCEEDED")));
        assertEquals(1, response.events().stream()
                .filter(event -> event.type().equals("TURN_CODEX"))
                .count());
        assertEquals(1, sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(
                        fixture.session().getId()).stream()
                .filter(turn -> turn.getActor() == SessionTurnActor.CODEX)
                .count());
    }

    @Test
    void disabledProgressGateKeepsLegacyTerminalEventAndPublishesNoProgress() {
        Fixture fixture = createRunningRun();
        completeRun(fixture.run().getId());
        progressService.append(fixture.run().getId(), AgentRunProgressCategory.COMPLETED);

        properties.setProgressEnabled(false);
        try {
            MobileSessionEventsResponse response = eventService.getEvents(
                    fixture.session().getId(), null, 200);

            assertTrue(response.events().stream()
                    .anyMatch(event -> event.type().equals("RUN_SUCCEEDED")));
            assertFalse(response.events().stream()
                    .anyMatch(event -> event.type().startsWith("RUN_PROGRESS_")));
            assertEquals(1, response.events().stream()
                    .filter(event -> event.type().equals("TURN_CODEX"))
                    .count());
        } finally {
            properties.setProgressEnabled(true);
        }
    }

    private Fixture createRunningRun() {
        String fixtureId = UUID.randomUUID().toString();
        Instant startedAt = Instant.parse("2026-07-31T12:00:00Z");

        ProjectEntity project = new ProjectEntity();
        project.setName("stream-project-" + fixtureId);
        project.setRepoPath("/workspace/repos/internal/stream-" + fixtureId);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(startedAt);
        project.setUpdatedAt(startedAt);
        project = projectRepository.save(project);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Shared stream " + fixtureId);
        session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:stream:" + fixtureId);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(startedAt);
        session.setLastActivityAt(startedAt.plusSeconds(30));
        session.setCreatedAt(startedAt);
        session.setUpdatedAt(startedAt.plusSeconds(30));
        session = workSessionRepository.save(session);

        SessionTurnEntity operatorTurn = new SessionTurnEntity();
        operatorTurn.setSession(session);
        operatorTurn.setActor(SessionTurnActor.OPERATOR);
        operatorTurn.setMessageText("Synthetic operator turn");
        operatorTurn.setCreatedAt(startedAt);
        operatorTurn = sessionTurnRepository.save(operatorTurn);

        SessionTurnEntity codexTurn = new SessionTurnEntity();
        codexTurn.setSession(session);
        codexTurn.setActor(SessionTurnActor.CODEX);
        codexTurn.setMessageText("Synthetic final response");
        codexTurn.setCreatedAt(startedAt.plusSeconds(30));
        codexTurn = sessionTurnRepository.save(codexTurn);

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(operatorTurn);
        run.setResultTurn(codexTurn);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setTargetRepoPath(project.getRepoPath());
        run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setStartedAt(startedAt);
        run.setOutputSummary("Synthetic final response");
        run.setCreatedAt(startedAt);
        run = agentRunRepository.saveAndFlush(run);
        return new Fixture(session, run);
    }

    private void completeRun(Long runId) {
        AgentRunEntity run = agentRunRepository.findById(runId).orElseThrow();
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setFinishedAt(run.getStartedAt().plusSeconds(30));
        agentRunRepository.saveAndFlush(run);
    }

    private record Fixture(WorkSessionEntity session, AgentRunEntity run) {
    }
}
