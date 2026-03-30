package com.atenea.api.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.AteneaApplication;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.AgentRunService;
import com.atenea.service.worksession.SessionDeliverableCodexOrchestrator;
import com.atenea.service.worksession.SessionCodexOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "atenea.auth.bootstrap.enabled=true",
        "atenea.auth.bootstrap.email=operator@atenea.local",
        "atenea.auth.bootstrap.password=secret-pass",
        "atenea.auth.bootstrap.display-name=Integration Operator",
        "atenea.auth.jwt.secret=integration-mobile-secret-2026"
})
class WorkSessionFlowIntegrationTest {

    private static final Path WORKSPACE_ROOT = Path.of("/workspace/repos");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private SessionTurnRepository sessionTurnRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private SessionDeliverableRepository sessionDeliverableRepository;

    @Autowired
    private OperatorRepository operatorRepository;

    @Autowired
    private AgentRunService agentRunService;

    private String mobileAccessToken;

    @MockBean
    private SessionCodexOrchestrator sessionCodexOrchestrator;

    @MockBean
    private SessionDeliverableCodexOrchestrator sessionDeliverableCodexOrchestrator;

    @MockBean
    private GitHubClient gitHubClient;

    @BeforeEach
    void setUp() {
        reset(sessionCodexOrchestrator);
        reset(sessionDeliverableCodexOrchestrator);
        reset(gitHubClient);
        agentRunRepository.deleteAll();
        sessionDeliverableRepository.deleteAll();
        sessionTurnRepository.deleteAll();
        workSessionRepository.deleteAll();
        projectRepository.deleteAll();
        mobileAccessToken = null;
    }

    @Test
    void endToEndHappyPathCoversOpenTurnsListsAndClose() throws Exception {
        ProjectEntity project = createProject("flow-happy-path");

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("First turn"), eq(null), any()))
                .thenReturn(completedHandle("thread-stable", "turn-a", "First answer"));
        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Second turn"), eq("thread-stable"), any()))
                .thenReturn(completedHandle("thread-stable", "turn-b", "Second answer"));

        JsonNode openedSession = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Integration flow"
                }
                """, 201);
        long sessionId = openedSession.get("id").asLong();
        assertEquals("atenea/session-" + sessionId, openedSession.get("workspaceBranch").asText());

        JsonNode fetchedSession = getJson("/api/sessions/%d".formatted(sessionId), 200);
        assertEquals("OPEN", fetchedSession.get("status").asText());
        assertTrue(fetchedSession.get("externalThreadId").isNull());
        assertEquals("atenea/session-" + sessionId, fetchedSession.get("workspaceBranch").asText());
        assertEquals("atenea/session-" + sessionId, fetchedSession.get("repoState").get("currentBranch").asText());

        JsonNode firstTurn = postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "First turn"
                }
                """, 201);
        assertEquals("turn-a", firstTurn.get("run").get("externalTurnId").asText());
        assertTrue(firstTurn.get("codexTurn").isNull());

        JsonNode secondTurn = postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Second turn"
                }
                """, 201);
        assertEquals("turn-b", secondTurn.get("run").get("externalTurnId").asText());
        assertTrue(secondTurn.get("codexTurn").isNull());

        waitUntil(() -> sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size() == 4);
        waitUntil(() -> agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .allMatch(run -> run.getStatus() == AgentRunStatus.SUCCEEDED));

        JsonNode turns = getJson("/api/sessions/%d/turns".formatted(sessionId), 200);
        assertEquals(4, turns.size());
        assertEquals("OPERATOR", turns.get(0).get("actor").asText());
        assertEquals("CODEX", turns.get(1).get("actor").asText());
        assertEquals("OPERATOR", turns.get(2).get("actor").asText());
        assertEquals("CODEX", turns.get(3).get("actor").asText());

        JsonNode runs = getJson("/api/sessions/%d/runs".formatted(sessionId), 200);
        assertEquals(2, runs.size());
        assertEquals("turn-a", runs.get(0).get("externalTurnId").asText());
        assertEquals("turn-b", runs.get(1).get("externalTurnId").asText());

        JsonNode closedSession = postJson("/api/sessions/%d/close".formatted(sessionId), null, 200);
        assertEquals("CLOSED", closedSession.get("status").asText());
        assertNotNull(closedSession.get("closedAt"));
        assertEquals("main", closedSession.get("repoState").get("currentBranch").asText());

        WorkSessionEntity persistedSession = workSessionRepository.findById(sessionId).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED, persistedSession.getStatus());
        assertEquals("main", runAndRead(Path.of(project.getRepoPath()), "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals("missing", runAndReadAllowingFailure(
                Path.of(project.getRepoPath()),
                "git", "rev-parse", "--verify", "--quiet", "refs/heads/atenea/session-" + sessionId));
    }

    @Test
    void continuityOfThreadIsStableAcrossTwoTurnsAndRunsUseDifferentTurnIds() throws Exception {
        ProjectEntity project = createProject("thread-continuity");

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("First turn"), eq(null), any()))
                .thenReturn(completedHandle("thread-stable", "turn-a", "First answer"));
        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Second turn"), eq("thread-stable"), any()))
                .thenReturn(completedHandle("thread-stable", "turn-b", "Second answer"));

        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Thread continuity"
                }
                """, 201).get("id").asLong();

        postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "First turn"
                }
                """, 201);
        waitUntil(() -> workSessionRepository.findById(sessionId)
                .map(WorkSessionEntity::getExternalThreadId)
                .filter("thread-stable"::equals)
                .isPresent());
        String threadAfterFirstTurn = getJson("/api/sessions/%d".formatted(sessionId), 200)
                .get("externalThreadId").asText();

        postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Second turn"
                }
                """, 201);
        waitUntil(() -> agentRunRepository.findAll().size() == 2
                && agentRunRepository.findAll().stream().allMatch(run -> run.getStatus() == AgentRunStatus.SUCCEEDED));
        String threadAfterSecondTurn = getJson("/api/sessions/%d".formatted(sessionId), 200)
                .get("externalThreadId").asText();

        assertEquals("thread-stable", threadAfterFirstTurn);
        assertEquals(threadAfterFirstTurn, threadAfterSecondTurn);

        List<AgentRunEntity> runs = agentRunRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(AgentRunEntity::getCreatedAt))
                .toList();
        assertEquals(2, runs.size());
        assertEquals("turn-a", runs.get(0).getExternalTurnId());
        assertEquals("turn-b", runs.get(1).getExternalTurnId());
    }

    @Test
    void doesNotAllowSecondOpenSessionForSameProject() throws Exception {
        ProjectEntity project = createProject("single-open-session");

        postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "First session"
                }
                """, 201);

        mockMvc.perform(post("/api/projects/%d/sessions".formatted(project.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Second session"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void doesNotAllowSecondRunningRunThroughPublicTurnFlow() throws Exception {
        ProjectEntity project = createProject("single-running-run");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Run invariant"
                }
                """, 201).get("id").asLong();

        agentRunService.createRunningRun(sessionId);

        mockMvc.perform(post("/api/sessions/%d/turns".formatted(sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Should fail"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void doesNotAllowClosingSessionWithRunningRun() throws Exception {
        ProjectEntity project = createProject("close-blocked");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Close blocked"
                }
                """, 201).get("id").asLong();

        agentRunService.createRunningRun(sessionId);

        mockMvc.perform(post("/api/sessions/%d/close".formatted(sessionId)))
                .andExpect(status().isConflict());

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSING, session.getStatus());
        assertEquals("running_run", session.getCloseBlockedState());
    }

    @Test
    void closeConversationViewReturnsBlockedRecoveryGuidance() throws Exception {
        ProjectEntity project = createProject("close-conversation-blocked");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Close blocked guidance"
                }
                """, 201).get("id").asLong();

        agentRunService.createRunningRun(sessionId);

        mockMvc.perform(post("/api/sessions/%d/close/conversation-view".formatted(sessionId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.state").value("running_run"))
                .andExpect(jsonPath("$.reason").value("WorkSession still has a running AgentRun"))
                .andExpect(jsonPath("$.action").value("Wait for the run to finish or reconcile it before retrying close"))
                .andExpect(jsonPath("$.retryable").value(true));

        JsonNode conversationView = getJson("/api/sessions/%d/conversation-view".formatted(sessionId), 200);
        assertEquals("CLOSING", conversationView.get("view").get("session").get("status").asText());
        assertEquals("running_run", conversationView.get("view").get("session").get("closeBlockedState").asText());
        assertEquals(
                "Wait for the run to finish or reconcile it before retrying close",
                conversationView.get("view").get("session").get("closeBlockedAction").asText());
    }

    @Test
    void recoversStaleRunningRunWhenSessionStateIsLoadedAgain() throws Exception {
        ProjectEntity project = createProject("stale-running-recovery");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Recover stale run"
                }
                """, 201).get("id").asLong();

        AgentRunEntity staleRun = agentRunService.createRunningRun(sessionId);
        staleRun.setStartedAt(Instant.now().minusSeconds(1900));
        agentRunRepository.save(staleRun);

        JsonNode conversationView = getJson("/api/sessions/%d/conversation-view".formatted(sessionId), 200);
        assertEquals("IDLE", conversationView.get("view").get("session").get("operationalState").asText());
        assertEquals("FAILED", conversationView.get("view").get("latestRun").get("status").asText());
        assertEquals(
                "Marked FAILED during reconciliation because the run stayed RUNNING past the stale timeout window",
                conversationView.get("view").get("lastError").asText());

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Recover now"), eq(null), any()))
                .thenReturn(completedHandle("thread-recovered", "turn-recovered", "Recovered answer"));

        JsonNode createTurn = postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Recover now"
                }
                """, 201);
        assertEquals("RUNNING", createTurn.get("run").get("status").asText());
        assertTrue(createTurn.get("codexTurn").isNull());

        waitUntil(() -> agentRunRepository.findAll().stream()
                .filter(run -> !run.getId().equals(staleRun.getId()))
                .anyMatch(run -> run.getStatus() == AgentRunStatus.SUCCEEDED));

        AgentRunEntity reconciledRun = agentRunRepository.findById(staleRun.getId()).orElseThrow();
        assertEquals(AgentRunStatus.FAILED, reconciledRun.getStatus());
        assertNotNull(reconciledRun.getFinishedAt());
    }

    @Test
    void createTurnConversationViewReturnsCanonicalSessionStateWithoutExtraRefresh() throws Exception {
        ProjectEntity project = createProject("turn-conversation-view");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Conversation contract"
                }
                """, 201).get("id").asLong();

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Inspect the project"), eq(null), any()))
                .thenReturn(completedHandle("thread-contract", "turn-contract", "Current state summary"));

        JsonNode createTurn = postJson("/api/sessions/%d/turns/conversation-view".formatted(sessionId), """
                {
                  "message": "Inspect the project"
                }
                """, 201);

        assertEquals(sessionId, createTurn.get("view").get("view").get("session").get("id").asLong());
        assertEquals("OPEN", createTurn.get("view").get("view").get("session").get("status").asText());
        assertEquals("SUCCEEDED", createTurn.get("view").get("view").get("latestRun").get("status").asText());
        assertEquals("OPERATOR", createTurn.get("view").get("recentTurns").get(0).get("actor").asText());
        assertEquals("Inspect the project", createTurn.get("view").get("recentTurns").get(0).get("messageText").asText());
        assertEquals("CODEX", createTurn.get("view").get("recentTurns").get(1).get("actor").asText());
        assertEquals("Current state summary", createTurn.get("view").get("recentTurns").get(1).get("messageText").asText());

        waitUntil(() -> agentRunRepository.findAll().stream().anyMatch(run -> run.getStatus() == AgentRunStatus.SUCCEEDED));

        JsonNode refreshedView = getJson("/api/sessions/%d/conversation-view".formatted(sessionId), 200);
        assertEquals("CODEX", refreshedView.get("recentTurns").get(1).get("actor").asText());
        assertEquals("Current state summary", refreshedView.get("recentTurns").get(1).get("messageText").asText());
    }

    @Test
    void closedSessionDoesNotAcceptNewTurns() throws Exception {
        ProjectEntity project = createProject("closed-session-turns");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Close then turn"
                }
                """, 201).get("id").asLong();

        postJson("/api/sessions/%d/close".formatted(sessionId), null, 200);

        mockMvc.perform(post("/api/sessions/%d/turns".formatted(sessionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "Should be blocked"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void publishSyncMergedAndCloseConversationViewCompletesCanonicalDeliveryFlow() throws Exception {
        ProjectEntity project = createProject("publish-sync-close-flow");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Publish flow"
                }
                """, 201).get("id").asLong();

        Files.writeString(Path.of(project.getRepoPath()).resolve("feature.txt"), "session work" + System.lineSeparator());

        when(gitHubClient.resolveRepository(projectRemoteUrl(project)))
                .thenReturn(new GitHubRepositoryRef("acme", "publish-sync-close-flow"));
        when(gitHubClient.createPullRequest(
                eq(new GitHubRepositoryRef("acme", "publish-sync-close-flow")),
                eq("Publish flow"),
                any(),
                eq("atenea/session-" + sessionId),
                eq("main")))
                .thenReturn(new GitHubPullRequest(
                        42L,
                        "https://github.com/acme/publish-sync-close-flow/pull/42",
                        "open",
                        false));
        when(gitHubClient.extractPullRequestNumber("https://github.com/acme/publish-sync-close-flow/pull/42"))
                .thenReturn(42L);
        when(gitHubClient.getPullRequest(new GitHubRepositoryRef("acme", "publish-sync-close-flow"), 42L))
                .thenReturn(new GitHubPullRequest(
                        42L,
                        "https://github.com/acme/publish-sync-close-flow/pull/42",
                        "closed",
                        true));

        JsonNode published = postJson("/api/sessions/%d/publish/conversation-view".formatted(sessionId), "{}", 200);
        assertEquals("OPEN", published.get("view").get("view").get("session").get("status").asText());
        assertEquals("OPEN", published.get("view").get("view").get("session").get("pullRequestStatus").asText());
        assertTrue(!published.get("view").get("view").get("session").get("publishedAt").isNull());

        JsonNode synced = postJson("/api/sessions/%d/pull-request/sync/conversation-view".formatted(sessionId), null, 200);
        assertEquals("MERGED", synced.get("view").get("view").get("session").get("pullRequestStatus").asText());

        JsonNode closed = postJson("/api/sessions/%d/close/conversation-view".formatted(sessionId), null, 200);
        assertEquals("CLOSED", closed.get("view").get("view").get("session").get("status").asText());
        assertEquals("MERGED", closed.get("view").get("view").get("session").get("pullRequestStatus").asText());
        assertEquals("main", closed.get("view").get("view").get("session").get("repoState").get("currentBranch").asText());

        WorkSessionEntity persistedSession = workSessionRepository.findById(sessionId).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED, persistedSession.getStatus());
        assertEquals(WorkSessionPullRequestStatus.MERGED, persistedSession.getPullRequestStatus());
        assertEquals("main", runAndRead(Path.of(project.getRepoPath()), "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals("missing", runAndReadAllowingFailure(
                Path.of(project.getRepoPath()),
                "git", "rev-parse", "--verify", "--quiet", "refs/heads/atenea/session-" + sessionId));
    }

    @Test
    void newFlowReturnsNotFoundForMissingSessionEndpoints() throws Exception {
        mockMvc.perform(get("/api/sessions/999999/turns"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/sessions/999999/runs"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/sessions/999999/deliverables"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/sessions/999999/close"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deliverablesEndpointsReturnLatestVersionPerTypeAndDetailedPayload() throws Exception {
        ProjectEntity project = createProject("session-deliverables");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Deliverables session"
                }
                """, 201).get("id").asLong();

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Ticket v1",
                "# Ticket v1",
                "{\"summary\":\"v1\"}",
                false);
        SessionDeliverableEntity latestTicket = persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUCCEEDED,
                2,
                "Ticket v2",
                "# Ticket v2\n\nFinal summary",
                "{\"summary\":\"v2\"}",
                true);
        persistDeliverable(
                session,
                SessionDeliverableType.WORK_BREAKDOWN,
                SessionDeliverableStatus.FAILED,
                1,
                "Breakdown",
                null,
                null,
                false);

        JsonNode deliverables = getJson("/api/sessions/%d/deliverables".formatted(sessionId), 200);
        assertEquals(sessionId, deliverables.get("sessionId").asLong());
        assertEquals(2, deliverables.get("deliverables").size());
        assertEquals("WORK_BREAKDOWN", deliverables.get("deliverables").get(0).get("type").asText());
        assertEquals("WORK_TICKET", deliverables.get("deliverables").get(1).get("type").asText());
        assertEquals(2, deliverables.get("deliverables").get(1).get("version").asInt());
        assertEquals(false, deliverables.get("allCoreDeliverablesPresent").asBoolean());
        assertEquals(false, deliverables.get("allCoreDeliverablesApproved").asBoolean());

        JsonNode detailed = getJson("/api/sessions/%d/deliverables/%d".formatted(sessionId, latestTicket.getId()), 200);
        assertEquals("WORK_TICKET", detailed.get("type").asText());
        assertEquals("# Ticket v2\n\nFinal summary", detailed.get("contentMarkdown").asText());
        assertEquals("{\"summary\":\"v2\"}", detailed.get("contentJson").asText());
        assertEquals(true, detailed.get("approved").asBoolean());
    }

    @Test
    void generateWorkTicketCreatesSucceededDeliverableWithSnapshot() throws Exception {
        ProjectEntity project = createProject("generate-work-ticket");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Generate deliverable"
                }
                """, 201).get("id").asLong();

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Summarize the work"), eq(null), any()))
                .thenReturn(completedHandle("thread-ticket", "turn-ticket", "Summary answer"));
        postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Summarize the work"
                }
                """, 201);
        waitUntil(() -> sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size() == 2);

        when(sessionDeliverableCodexOrchestrator.generateWorkTicket(eq(project.getRepoPath()), any()))
                .thenReturn("""
                        # Work Ticket

                        ## Completed Work

                        - Summarized the work session
                        """);

        JsonNode generated = postJson(
                "/api/sessions/%d/deliverables/WORK_TICKET/generate".formatted(sessionId),
                "{}",
                201);

        assertEquals("WORK_TICKET", generated.get("type").asText());
        assertEquals("SUCCEEDED", generated.get("status").asText());
        assertEquals(1, generated.get("version").asInt());
        assertTrue(generated.get("contentMarkdown").asText().contains("# Work Ticket"));
        assertTrue(generated.get("inputSnapshotJson").asText().contains("\"turns\""));
        assertEquals("session-deliverables-work-ticket-v1", generated.get("promptVersion").asText());

        JsonNode listed = getJson("/api/sessions/%d/deliverables".formatted(sessionId), 200);
        assertEquals(1, listed.get("deliverables").size());
        assertEquals("WORK_TICKET", listed.get("deliverables").get(0).get("type").asText());
        assertEquals(1, listed.get("deliverables").get(0).get("version").asInt());
    }

    @Test
    void generateWorkBreakdownCreatesSucceededDeliverableWithOwnVersioning() throws Exception {
        ProjectEntity project = createProject("generate-work-breakdown");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Generate breakdown"
                }
                """, 201).get("id").asLong();

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Implement feature summary"), eq(null), any()))
                .thenReturn(completedHandle("thread-breakdown", "turn-breakdown", "Feature implemented"));
        postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Implement feature summary"
                }
                """, 201);
        waitUntil(() -> sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size() == 2);

        when(sessionDeliverableCodexOrchestrator.generateWorkBreakdown(eq(project.getRepoPath()), any()))
                .thenReturn("""
                        # Work Breakdown

                        ## Completed Work Items

                        - Implemented the requested feature summary
                        """);

        JsonNode generated = postJson(
                "/api/sessions/%d/deliverables/WORK_BREAKDOWN/generate".formatted(sessionId),
                "{}",
                201);

        assertEquals("WORK_BREAKDOWN", generated.get("type").asText());
        assertEquals("SUCCEEDED", generated.get("status").asText());
        assertEquals(1, generated.get("version").asInt());
        assertTrue(generated.get("contentMarkdown").asText().contains("# Work Breakdown"));
        assertEquals("session-deliverables-work-breakdown-v1", generated.get("promptVersion").asText());

        JsonNode listed = getJson("/api/sessions/%d/deliverables".formatted(sessionId), 200);
        assertEquals(1, listed.get("deliverables").size());
        assertEquals("WORK_BREAKDOWN", listed.get("deliverables").get(0).get("type").asText());
        assertEquals(1, listed.get("deliverables").get(0).get("version").asInt());
    }

    @Test
    void generatePriceEstimateCreatesSucceededDeliverableWithPricingPolicySnapshot() throws Exception {
        ProjectEntity project = createProject("generate-price-estimate");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Generate price estimate"
                }
                """, 201).get("id").asLong();

        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Implement and explain the change"), eq(null), any()))
                .thenReturn(completedHandle("thread-price", "turn-price", "Implementation completed"));
        postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Implement and explain the change"
                }
                """, 201);
        waitUntil(() -> sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).size() == 2);

        when(sessionDeliverableCodexOrchestrator.generatePriceEstimate(eq(project.getRepoPath()), any()))
                .thenReturn(new SessionDeliverableCodexOrchestrator.PriceEstimateGenerationResult(
                        """
                        # Price Estimate

                        ## Equivalent Hours

                        - 6.5 horas equivalentes internas

                        ## Price Range

                        - 240 EUR to 320 EUR base imponible

                        ## Recommended Fixed Price

                        - 279 EUR base imponible
                        """,
                        """
                        {
                          "currency": "EUR",
                          "baseHourlyRate": 43.0,
                          "equivalentHours": 6.5,
                          "minimumPrice": 240.0,
                          "recommendedPrice": 279.0,
                          "maximumPrice": 320.0,
                          "commercialPositioning": "competitive",
                          "riskLevel": "low",
                          "confidence": "medium",
                          "assumptions": [
                            "Se usa solo la evidencia de esta session"
                          ],
                          "exclusions": [
                            "No incluye soporte posterior"
                          ]
                        }
                        """));

        JsonNode generated = postJson(
                "/api/sessions/%d/deliverables/PRICE_ESTIMATE/generate".formatted(sessionId),
                "{}",
                201);

        assertEquals("PRICE_ESTIMATE", generated.get("type").asText());
        assertEquals("SUCCEEDED", generated.get("status").asText());
        assertEquals(1, generated.get("version").asInt());
        assertTrue(generated.get("contentMarkdown").asText().contains("# Price Estimate"));
        assertEquals("EUR", objectMapper.readTree(generated.get("contentJson").asText()).get("currency").asText());
        assertEquals(279.0d, objectMapper.readTree(generated.get("contentJson").asText()).get("recommendedPrice").asDouble());
        assertTrue(generated.get("inputSnapshotJson").asText().contains("\"pricingPolicy\""));
        assertTrue(generated.get("inputSnapshotJson").asText().contains("\"baseHourlyRate\":43.0"));
        assertEquals("session-deliverables-price-estimate-v2", generated.get("promptVersion").asText());
        assertEquals(false, generated.get("approved").asBoolean());

        JsonNode listed = getJson("/api/sessions/%d/deliverables".formatted(sessionId), 200);
        assertEquals(1, listed.get("deliverables").size());
        assertEquals("PRICE_ESTIMATE", listed.get("deliverables").get(0).get("type").asText());
        assertEquals(1, listed.get("deliverables").get(0).get("version").asInt());
    }

    @Test
    void approveDeliverableExposesItThroughApprovedReadModel() throws Exception {
        ProjectEntity project = createProject("approve-deliverable");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Approve deliverable"
                }
                """, 201).get("id").asLong();

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        SessionDeliverableEntity deliverable = persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Work ticket v1",
                "# Work Ticket",
                "{\"summary\":\"done\"}",
                false);

        JsonNode approved = postJson(
                "/api/sessions/%d/deliverables/%d/approve".formatted(sessionId, deliverable.getId()),
                "{}",
                200);
        assertEquals(true, approved.get("approved").asBoolean());
        assertTrue(!approved.get("approvedAt").isNull());

        JsonNode approvedView = getJson("/api/sessions/%d/deliverables/approved".formatted(sessionId), 200);
        assertEquals(1, approvedView.get("deliverables").size());
        assertEquals("WORK_TICKET", approvedView.get("deliverables").get(0).get("type").asText());
        assertEquals(true, approvedView.get("deliverables").get(0).get("approved").asBoolean());
        assertEquals(true, approvedView.get("allCoreDeliverablesPresent").asBoolean() == false);
    }

    @Test
    void generatePriceEstimateFailsWhenStructuredJsonIsInvalid() throws Exception {
        ProjectEntity project = createProject("generate-price-estimate-invalid-json");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Generate invalid price estimate"
                }
                """, 201).get("id").asLong();

        when(sessionDeliverableCodexOrchestrator.generatePriceEstimate(eq(project.getRepoPath()), any()))
                .thenReturn(new SessionDeliverableCodexOrchestrator.PriceEstimateGenerationResult(
                        "# Price Estimate\n\nBroken structured output",
                        """
                        {
                          "currency": "EUR",
                          "recommendedPrice": 279.0
                        }
                        """));

        JsonNode generated = postJson(
                "/api/sessions/%d/deliverables/PRICE_ESTIMATE/generate".formatted(sessionId),
                "{}",
                201);

        assertEquals("FAILED", generated.get("status").asText());
        assertTrue(generated.get("errorMessage").asText().contains("baseHourlyRate"));
    }

    @Test
    void deliverableHistoryReturnsAllVersionsAndApprovedBaseline() throws Exception {
        ProjectEntity project = createProject("deliverable-history");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Deliverable history"
                }
                """, 201).get("id").asLong();

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        SessionDeliverableEntity version1 = persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUPERSEDED,
                1,
                "Work ticket v1",
                "# Work Ticket v1",
                "{\"summary\":\"v1\"}",
                true);
        SessionDeliverableEntity version2 = persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUCCEEDED,
                2,
                "Work ticket v2",
                "# Work Ticket v2",
                "{\"summary\":\"v2\"}",
                false);

        JsonNode history = getJson("/api/sessions/%d/deliverables/types/WORK_TICKET/history".formatted(sessionId), 200);
        assertEquals("WORK_TICKET", history.get("type").asText());
        assertEquals(version2.getId(), history.get("latestGeneratedDeliverableId").asLong());
        assertEquals(version1.getId(), history.get("latestApprovedDeliverableId").asLong());
        assertEquals(2, history.get("versions").size());
        assertEquals(2, history.get("versions").get(0).get("version").asInt());
        assertEquals(1, history.get("versions").get(1).get("version").asInt());
        assertEquals(version1.getId(), history.get("versions").get(0).get("latestApprovedDeliverableId").asLong());
    }

    @Test
    void approvedPriceEstimateSummaryReturnsStructuredFieldsFromApprovedVersion() throws Exception {
        ProjectEntity project = createProject("approved-price-estimate-summary");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Approved pricing summary"
                }
                """, 201).get("id").asLong();

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        persistDeliverable(
                session,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate v1",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":6.5,"minimumPrice":240.0,
                "recommendedPrice":279.0,"maximumPrice":320.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Solo trabajo de la session"],
                "exclusions":["No incluye soporte posterior"]}
                """,
                true);

        JsonNode summary = getJson("/api/sessions/%d/deliverables/price-estimate/approved-summary".formatted(sessionId), 200);
        assertEquals("EUR", summary.get("currency").asText());
        assertEquals(279.0d, summary.get("recommendedPrice").asDouble());
        assertEquals(6.5d, summary.get("equivalentHours").asDouble());
        assertEquals("competitive", summary.get("commercialPositioning").asText());
        assertEquals("READY", summary.get("billingStatus").asText());
        assertEquals(1, summary.get("assumptions").size());
    }

    @Test
    void markPriceEstimateBilledExposesCommercialTrackingInSessionAndProjectReads() throws Exception {
        ProjectEntity project = createProject("billing-state");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Billing state"
                }
                """, 201).get("id").asLong();

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        SessionDeliverableEntity estimate = persistDeliverable(
                session,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate v1",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":6.5,"minimumPrice":240.0,
                "recommendedPrice":279.0,"maximumPrice":320.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Billing test"],"exclusions":["No soporte"]}
                """,
                true);

        JsonNode billed = postJson("/api/sessions/%d/deliverables/%d/billing/mark-billed".formatted(sessionId, estimate.getId()), """
                {
                  "billingReference": "INV-2026-001"
                }
                """, 200);
        assertEquals("BILLED", billed.get("billingStatus").asText());
        assertEquals("INV-2026-001", billed.get("billingReference").asText());
        assertTrue(!billed.get("billedAt").isNull());

        JsonNode sessionSummary = getJson("/api/sessions/%d/deliverables/price-estimate/approved-summary".formatted(sessionId), 200);
        assertEquals("BILLED", sessionSummary.get("billingStatus").asText());
        assertEquals("INV-2026-001", sessionSummary.get("billingReference").asText());

        JsonNode projectSummary = getJson("/api/projects/%d/approved-price-estimates".formatted(project.getId()), 200);
        assertEquals("BILLED", projectSummary.get("approvedPriceEstimates").get(0).get("billingStatus").asText());
        assertEquals("INV-2026-001", projectSummary.get("approvedPriceEstimates").get(0).get("billingReference").asText());
    }

    @Test
    void billingQueueReturnsCurrentApprovedBaselinesWithFiltersAndSummary() throws Exception {
        ProjectEntity projectOne = createProject("billing-queue-one");
        ProjectEntity projectTwo = createProject("billing-queue-two");

        WorkSessionEntity sessionOne = workSessionRepository.findById(postJson("/api/projects/%d/sessions".formatted(projectOne.getId()), """
                {
                  "title": "Ready baseline"
                }
                """, 201).get("id").asLong()).orElseThrow();
        WorkSessionEntity sessionTwo = workSessionRepository.findById(postJson("/api/projects/%d/sessions".formatted(projectTwo.getId()), """
                {
                  "title": "Already billed baseline"
                }
                """, 201).get("id").asLong()).orElseThrow();

        SessionDeliverableEntity readyEstimate = persistDeliverable(
                sessionOne,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate ready",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":6.5,"minimumPrice":240.0,
                "recommendedPrice":279.0,"maximumPrice":320.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Queue ready"],"exclusions":["No soporte"]}
                """,
                true);
        SessionDeliverableEntity billedEstimate = persistDeliverable(
                sessionTwo,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate billed",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":8.0,"minimumPrice":300.0,
                "recommendedPrice":360.0,"maximumPrice":420.0,"commercialPositioning":"premium",
                "riskLevel":"medium","confidence":"medium","assumptions":["Queue billed"],"exclusions":["No soporte"]}
                """,
                true);

        postJson("/api/sessions/%d/deliverables/%d/billing/mark-billed".formatted(sessionTwo.getId(), billedEstimate.getId()), """
                {
                  "billingReference": "INV-2026-002"
                }
                """, 200);

        JsonNode queue = getJson("/api/billing/queue", 200);
        assertEquals(2, queue.get("items").size());
        assertEquals("READY", queue.get("items").get(0).get("billingStatus").asText());
        assertEquals(readyEstimate.getId(), queue.get("items").get(0).get("deliverableId").asLong());
        assertEquals("BILLED", queue.get("items").get(1).get("billingStatus").asText());
        assertEquals("INV-2026-002", queue.get("items").get(1).get("billingReference").asText());

        JsonNode readyOnly = getJson("/api/billing/queue?billingStatus=READY", 200);
        assertEquals(1, readyOnly.get("items").size());
        assertEquals(sessionOne.getId(), readyOnly.get("items").get(0).get("sessionId").asLong());

        JsonNode search = getJson("/api/billing/queue?q=INV-2026-002", 200);
        assertEquals(1, search.get("items").size());
        assertEquals("BILLED", search.get("items").get(0).get("billingStatus").asText());

        JsonNode summary = getJson("/api/billing/queue/summary", 200);
        assertEquals(1, summary.get("readyCount").asInt());
        assertEquals(1, summary.get("billedCount").asInt());
        assertEquals(279.0d, summary.get("readyAmounts").get(0).get("total").asDouble());
        assertEquals(360.0d, summary.get("billedAmounts").get(0).get("total").asDouble());
    }

    @Test
    void mobileSessionSummaryAndEventsExposeConversationDeliveryAndCommercialState() throws Exception {
        ProjectEntity project = createProject("mobile-session-summary");
        when(sessionCodexOrchestrator.startTurn(eq(project.getRepoPath()), eq("Summarize for mobile"), eq(null), any()))
                .thenReturn(completedHandle("thread-mobile", "turn-mobile", "Mobile summary answer"));

        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Mobile summary"
                }
                """, 201).get("id").asLong();

        postJson("/api/sessions/%d/turns".formatted(sessionId), """
                {
                  "message": "Summarize for mobile"
                }
                """, 201);
        waitUntil(() -> agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .anyMatch(run -> run.getStatus() == AgentRunStatus.SUCCEEDED));

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        SessionDeliverableEntity estimate = persistDeliverable(
                session,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate mobile",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":6.5,"minimumPrice":240.0,
                "recommendedPrice":279.0,"maximumPrice":320.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Mobile"],"exclusions":["No soporte"]}
                """,
                true);

        JsonNode mobileSummary = getMobileJson("/api/mobile/sessions/%d/summary".formatted(sessionId), 200);
        assertEquals(sessionId, mobileSummary.get("conversation").get("view").get("session").get("id").asLong());
        assertEquals("READY", mobileSummary.get("approvedPriceEstimate").get("billingStatus").asText());
        assertEquals(true, mobileSummary.get("actions").get("canMarkApprovedPriceEstimateBilled").asBoolean());

        postMobileJson("/api/mobile/sessions/%d/deliverables/%d/billing/mark-billed".formatted(sessionId, estimate.getId()), """
                {
                  "billingReference": "INV-MOBILE-1"
                }
                """, 200);

        JsonNode events = getMobileJson("/api/mobile/sessions/%d/events".formatted(sessionId), 200);
        assertEquals(sessionId, events.get("sessionId").asLong());
        assertTrue(events.get("events").size() >= 4);
        assertEquals("DELIVERABLE_BILLED", events.get("events").get(0).get("type").asText());
    }

    @Test
    void mobileInboxHighlightsRunningBlockedAndBillingAttention() throws Exception {
        ProjectEntity runningProject = createProject("mobile-inbox-running");
        long runningSessionId = postJson("/api/projects/%d/sessions".formatted(runningProject.getId()), """
                {
                  "title": "Running session"
                }
                """, 201).get("id").asLong();
        agentRunService.createRunningRun(runningSessionId);

        ProjectEntity blockedProject = createProject("mobile-inbox-blocked");
        long blockedSessionId = postJson("/api/projects/%d/sessions".formatted(blockedProject.getId()), """
                {
                  "title": "Blocked session"
                }
                """, 201).get("id").asLong();
        agentRunService.createRunningRun(blockedSessionId);
        mockMvc.perform(post("/api/sessions/%d/close".formatted(blockedSessionId)))
                .andExpect(status().isConflict());

        ProjectEntity billingProject = createProject("mobile-inbox-billing");
        long billingSessionId = postJson("/api/projects/%d/sessions".formatted(billingProject.getId()), """
                {
                  "title": "Billing session"
                }
                """, 201).get("id").asLong();
        WorkSessionEntity billingSession = workSessionRepository.findById(billingSessionId).orElseThrow();
        persistDeliverable(
                billingSession,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate inbox",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":5.0,"minimumPrice":200.0,
                "recommendedPrice":240.0,"maximumPrice":280.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Inbox"],"exclusions":["No soporte"]}
                """,
                true);

        JsonNode inbox = getMobileJson("/api/mobile/inbox", 200);
        assertTrue(inbox.get("items").size() >= 3);
        assertEquals(2, inbox.get("summary").get("runInProgressCount").asInt());
        assertEquals(1, inbox.get("summary").get("closeBlockedCount").asInt());
        assertEquals(1, inbox.get("summary").get("billingReadyCount").asInt());
        assertEquals("CLOSE_BLOCKED", inbox.get("items").get(0).get("type").asText());
    }

    @Test
    void projectApprovedPriceEstimatesReturnsApprovedPricingAcrossSessions() throws Exception {
        ProjectEntity project = createProject("project-approved-price-estimates");
        WorkSessionEntity sessionOne = workSessionRepository.findById(postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Approved pricing one"
                }
                """, 201).get("id").asLong()).orElseThrow();
        sessionOne.setStatus(WorkSessionStatus.CLOSED);
        sessionOne.setClosedAt(Instant.now());
        sessionOne.setUpdatedAt(Instant.now());
        workSessionRepository.save(sessionOne);
        WorkSessionEntity sessionTwo = workSessionRepository.findById(postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Approved pricing two"
                }
                """, 201).get("id").asLong()).orElseThrow();

        persistDeliverable(
                sessionOne,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate v1",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":5.0,"minimumPrice":200.0,
                "recommendedPrice":240.0,"maximumPrice":280.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Session one"],"exclusions":["No soporte"]}
                """,
                true);
        persistDeliverable(
                sessionTwo,
                SessionDeliverableType.PRICE_ESTIMATE,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Price estimate v1",
                "# Price Estimate",
                """
                {"currency":"EUR","baseHourlyRate":43.0,"equivalentHours":6.5,"minimumPrice":240.0,
                "recommendedPrice":279.0,"maximumPrice":320.0,"commercialPositioning":"competitive",
                "riskLevel":"low","confidence":"medium","assumptions":["Session two"],"exclusions":["No soporte"]}
                """,
                true);

        JsonNode response = getJson("/api/projects/%d/approved-price-estimates".formatted(project.getId()), 200);
        assertEquals(project.getId(), response.get("projectId").asLong());
        assertEquals(2, response.get("approvedPriceEstimates").size());
    }

    @Test
    void regeneratingDeliverableSupersedesOlderUnapprovedVersion() throws Exception {
        ProjectEntity project = createProject("supersede-generated");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Supersede generated"
                }
                """, 201).get("id").asLong();

        when(sessionDeliverableCodexOrchestrator.generateWorkTicket(eq(project.getRepoPath()), any()))
                .thenReturn("# Work Ticket\n\nVersion 1");
        postJson("/api/sessions/%d/deliverables/WORK_TICKET/generate".formatted(sessionId), "{}", 201);

        when(sessionDeliverableCodexOrchestrator.generateWorkTicket(eq(project.getRepoPath()), any()))
                .thenReturn("# Work Ticket\n\nVersion 2");
        JsonNode second = postJson("/api/sessions/%d/deliverables/WORK_TICKET/generate".formatted(sessionId), "{}", 201);

        List<SessionDeliverableEntity> deliverables = sessionDeliverableRepository
                .findBySessionIdAndTypeOrderByVersionDesc(sessionId, SessionDeliverableType.WORK_TICKET);
        assertEquals(2, deliverables.size());
        assertEquals(2, second.get("version").asInt());
        assertEquals(SessionDeliverableStatus.SUCCEEDED, deliverables.get(0).getStatus());
        assertEquals(SessionDeliverableStatus.SUPERSEDED, deliverables.get(1).getStatus());
    }

    @Test
    void approvingNewVersionSupersedesOlderApprovedVersion() throws Exception {
        ProjectEntity project = createProject("supersede-approved");
        long sessionId = postJson("/api/projects/%d/sessions".formatted(project.getId()), """
                {
                  "title": "Supersede approved"
                }
                """, 201).get("id").asLong();

        WorkSessionEntity session = workSessionRepository.findById(sessionId).orElseThrow();
        SessionDeliverableEntity version1 = persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUCCEEDED,
                1,
                "Work ticket v1",
                "# Work Ticket v1",
                "{\"summary\":\"v1\"}",
                true);
        SessionDeliverableEntity version2 = persistDeliverable(
                session,
                SessionDeliverableType.WORK_TICKET,
                SessionDeliverableStatus.SUCCEEDED,
                2,
                "Work ticket v2",
                "# Work Ticket v2",
                "{\"summary\":\"v2\"}",
                false);

        postJson("/api/sessions/%d/deliverables/%d/approve".formatted(sessionId, version2.getId()), "{}", 200);

        SessionDeliverableEntity refreshedV1 = sessionDeliverableRepository.findById(version1.getId()).orElseThrow();
        SessionDeliverableEntity refreshedV2 = sessionDeliverableRepository.findById(version2.getId()).orElseThrow();
        assertEquals(SessionDeliverableStatus.SUPERSEDED, refreshedV1.getStatus());
        assertEquals(false, refreshedV1.isApproved());
        assertEquals(SessionDeliverableStatus.SUCCEEDED, refreshedV2.getStatus());
        assertEquals(true, refreshedV2.isApproved());
    }

    private JsonNode postJson(String path, String body, int expectedStatus) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(body);
        }

        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postMobileJson(String path, String body, int expectedStatus) throws Exception {
        var builder = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + getMobileAccessToken());
        if (body != null) {
            builder.content(body);
        }

        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getMobileJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header("Authorization", "Bearer " + getMobileAccessToken()))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String getMobileAccessToken() throws Exception {
        if (mobileAccessToken != null) {
            return mobileAccessToken;
        }
        if (operatorRepository.findByEmailIgnoreCase("operator@atenea.local").isEmpty()) {
            throw new IllegalStateException("Bootstrap operator was not created");
        }
        JsonNode login = postJson("/api/mobile/auth/login", """
                {
                  "email": "operator@atenea.local",
                  "password": "secret-pass"
                }
                """, 200);
        mobileAccessToken = login.get("accessToken").asText();
        return mobileAccessToken;
    }

    private ProjectEntity createProject(String slug) throws IOException {
        String unique = slug + "-" + UUID.randomUUID();
        Path repoPath = initializeGitRepository(unique);

        ProjectEntity project = new ProjectEntity();
        project.setName(unique);
        project.setDescription("Integration test project");
        project.setRepoPath(repoPath.toString());
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        return projectRepository.save(project);
    }

    private static CodexAppServerExecutionHandle completedHandle(String threadId, String turnId, String finalAnswer) {
        return new CodexAppServerExecutionHandle(
                threadId,
                turnId,
                CompletableFuture.completedFuture(new CodexAppServerExecutionResult(
                        threadId,
                        turnId,
                        CodexAppServerExecutionResult.Status.COMPLETED,
                        finalAnswer,
                        finalAnswer,
                        "commentary",
                        null)));
    }

    private static void waitUntil(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for asynchronous completion");
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    private Path initializeGitRepository(String slug) throws IOException {
        Path repoPath = WORKSPACE_ROOT.resolve("integration-tests").resolve(slug);
        Path remotePath = WORKSPACE_ROOT.resolve("integration-tests-remotes").resolve(slug + ".git");
        Files.createDirectories(repoPath);
        Files.createDirectories(remotePath.getParent());
        Files.writeString(repoPath.resolve("README.md"), "# " + slug + System.lineSeparator());
        runOrThrow(repoPath, "git", "init", "-b", "main");
        runOrThrow(remotePath.getParent(), "git", "init", "--bare", remotePath.toString());
        runOrThrow(repoPath, "git", "config", "user.email", "integration@atenea.local");
        runOrThrow(repoPath, "git", "config", "user.name", "Atenea Integration");
        runOrThrow(repoPath, "git", "add", "README.md");
        runOrThrow(repoPath, "git", "commit", "-m", "Initial commit");
        runOrThrow(repoPath, "git", "remote", "add", "origin", remotePath.toString());
        runOrThrow(repoPath, "git", "push", "-u", "origin", "main");
        return repoPath;
    }

    private SessionDeliverableEntity persistDeliverable(
            WorkSessionEntity session,
            SessionDeliverableType type,
            SessionDeliverableStatus status,
            int version,
            String title,
            String markdown,
            String contentJson,
            boolean approved
    ) {
        Instant now = Instant.now();
        SessionDeliverableEntity deliverable = new SessionDeliverableEntity();
        deliverable.setSession(session);
        deliverable.setType(type);
        deliverable.setStatus(status);
        deliverable.setVersion(version);
        deliverable.setTitle(title);
        deliverable.setContentMarkdown(markdown);
        deliverable.setContentJson(contentJson);
        deliverable.setInputSnapshotJson("{\"sessionId\":" + session.getId() + "}");
        deliverable.setGenerationNotes("Generated in integration test");
        deliverable.setErrorMessage(status == SessionDeliverableStatus.FAILED ? "Generation failed" : null);
        deliverable.setModel("gpt-5.4");
        deliverable.setPromptVersion("deliverables-v1");
        deliverable.setApproved(approved);
        deliverable.setApprovedAt(approved ? now : null);
        if (approved && type == SessionDeliverableType.PRICE_ESTIMATE) {
            deliverable.setBillingStatus(SessionDeliverableBillingStatus.READY);
        }
        deliverable.setCreatedAt(now);
        deliverable.setUpdatedAt(now);
        return sessionDeliverableRepository.save(deliverable);
    }

    private String projectRemoteUrl(ProjectEntity project) {
        return runAndRead(Path.of(project.getRepoPath()), "git", "remote", "get-url", "origin");
    }

    private static String runAndRead(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Command failed: " + String.join(" ", command));
            }
            return output.trim();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not run command: " + String.join(" ", command), exception);
        }
    }

    private static String runAndReadAllowingFailure(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 ? output : "missing";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not run command: " + String.join(" ", command), exception);
        }
    }

    private void runOrThrow(Path directory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Command failed: " + String.join(" ", command));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not run command: " + String.join(" ", command), exception);
        }
    }
}
