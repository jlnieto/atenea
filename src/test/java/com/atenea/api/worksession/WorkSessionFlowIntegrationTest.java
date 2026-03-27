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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.AteneaApplication;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.AgentRunService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = AteneaApplication.class)
@AutoConfigureMockMvc
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
    private AgentRunService agentRunService;

    @MockBean
    private SessionCodexOrchestrator sessionCodexOrchestrator;

    @BeforeEach
    void setUp() {
        reset(sessionCodexOrchestrator);
        agentRunRepository.deleteAll();
        sessionTurnRepository.deleteAll();
        workSessionRepository.deleteAll();
        projectRepository.deleteAll();
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

        JsonNode fetchedSession = getJson("/api/sessions/%d".formatted(sessionId), 200);
        assertEquals("OPEN", fetchedSession.get("status").asText());
        assertTrue(fetchedSession.get("externalThreadId").isNull());

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

        WorkSessionEntity persistedSession = workSessionRepository.findById(sessionId).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED, persistedSession.getStatus());
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
        assertEquals(WorkSessionStatus.OPEN, session.getStatus());
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
    void newFlowReturnsNotFoundForMissingSessionEndpoints() throws Exception {
        mockMvc.perform(get("/api/sessions/999999/turns"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/sessions/999999/runs"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/sessions/999999/close"))
                .andExpect(status().isNotFound());
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
        Files.createDirectories(repoPath);
        Files.writeString(repoPath.resolve("README.md"), "# " + slug + System.lineSeparator());
        runOrThrow(repoPath, "git", "init", "-b", "main");
        runOrThrow(repoPath, "git", "config", "user.email", "integration@atenea.local");
        runOrThrow(repoPath, "git", "config", "user.name", "Atenea Integration");
        runOrThrow(repoPath, "git", "add", "README.md");
        runOrThrow(repoPath, "git", "commit", "-m", "Initial commit");
        return repoPath;
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
