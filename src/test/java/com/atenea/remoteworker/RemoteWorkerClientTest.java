package com.atenea.remoteworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkloadClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteWorkerClientTest {
    private static final String TEST_CANONICAL_COMMIT = "1".repeat(40);

    @TempDir
    Path temporary;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private HttpServer server;
    private RemoteWorkerProperties properties;
    private RemoteWorkerClient client;

    @BeforeEach
    void setUp() throws IOException {
        Path token = temporary.resolve("worker.token");
        Files.writeString(token, "t".repeat(64));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/executions", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("dispatchId", request.get("dispatchId").asText()),
                    java.util.Map.entry("executionId", "4ee2d311-b9da-4307-89b6-dd3110ef2057"),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("workloadClass", request.get("workloadClass").asText()),
                    java.util.Map.entry("leaseGeneration", request.get("leaseGeneration").asLong()),
                    java.util.Map.entry("status", "QUEUED"),
                    java.util.Map.entry("statusReason", "Awaiting worker admission"),
                    java.util.Map.entry("revision", 1),
                    java.util.Map.entry("progress", 0),
                    java.util.Map.entry("createdAt", Instant.parse("2026-07-29T06:00:00Z")),
                    java.util.Map.entry("updatedAt", Instant.parse("2026-07-29T06:00:00Z"))));
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/project-workspaces/ensure", exchange -> {
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            JsonNode request = requestBody.get();
            byte[] response = objectMapper.writeValueAsBytes(java.util.Map.ofEntries(
                    java.util.Map.entry("state", "ready"),
                    java.util.Map.entry("sessionId", request.get("sessionId").asText()),
                    java.util.Map.entry("workspaceIdentity", request.get("workspaceIdentity").asText()),
                    java.util.Map.entry("projectId", request.get("projectId").asText()),
                    java.util.Map.entry("workspaceBranch", request.get("workspaceBranch").asText()),
                    java.util.Map.entry("slot", "slot4"),
                    java.util.Map.entry("canonicalCommit", request.get("commit").asText()),
                    java.util.Map.entry("selectionEnabled", true),
                    java.util.Map.entry("executionEnabled", true),
                    java.util.Map.entry("valuesExposed", false)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        properties = new RemoteWorkerProperties();
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTokenFile(token.toString());
        client = new RemoteWorkerClient(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void projectDispatchUsesOnlyPersistedExactIdentityAndContinuesThread() {
        UUID threadId = UUID.fromString("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        AgentRunEntity run = projectRun(threadId.toString());

        client.dispatch(run, "Update only the accepted documentation fixture.");

        JsonNode body = requestBody.get();
        JsonNode workload = body.get("workload");
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, workload.get("kind").asText());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, workload.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY, workload.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH, workload.get("branch").asText());
        assertEquals(TEST_CANONICAL_COMMIT, workload.get("commit").asText());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256, workload.get("manifestSha256").asText());
        assertEquals(threadId.toString(), workload.get("threadId").asText());
        assertNull(workload.get("command"));
        assertNull(workload.get("path"));
        assertNull(workload.get("endpoint"));
        assertNull(workload.get("environment"));
    }

    @Test
    void beautipsDispatchReusesPersistedIdentityAndContinuesThread() {
        UUID threadId = UUID.fromString("bcf43e2e-c9e8-42df-96b2-e9183462c2f4");
        AgentRunEntity run = beautipsRun(threadId.toString());

        client.dispatch(run, "Update only the accepted Beautips fixture.");

        JsonNode body = requestBody.get();
        JsonNode workload = body.get("workload");
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, workload.get("kind").asText());
        assertEquals(BeautipsProjectCodexIdentity.PROJECT_IDENTITY, workload.get("projectId").asText());
        assertEquals(BeautipsProjectCodexIdentity.REPOSITORY, workload.get("repository").asText());
        assertEquals(BeautipsProjectCodexIdentity.BRANCH, workload.get("branch").asText());
        assertEquals(BeautipsProjectCodexIdentity.COMMIT, workload.get("commit").asText());
        assertEquals(BeautipsProjectCodexIdentity.MANIFEST_SHA256, workload.get("manifestSha256").asText());
        assertEquals(threadId.toString(), workload.get("threadId").asText());
        assertNull(workload.get("command"));
        assertNull(workload.get("path"));
        assertNull(workload.get("endpoint"));
        assertNull(workload.get("environment"));
    }

    @Test
    void beautipsWorkspaceEnsureUsesOnlyPersistedExactIdentity() {
        AgentRunEntity run = beautipsRun(null);
        run.getSession().setWorkspaceBranch("atenea/session-11111111-1111-4111-8111-111111111111");

        RemoteWorkerClient.Workspace workspace = client.ensureWorkspace(run);

        JsonNode body = requestBody.get();
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(run.getRepositoryCommit(), workspace.canonicalCommit());
        assertEquals(BeautipsProjectCodexIdentity.PROJECT_IDENTITY, body.get("projectId").asText());
        assertEquals(BeautipsProjectCodexIdentity.REPOSITORY, body.get("repository").asText());
        assertEquals(BeautipsProjectCodexIdentity.BRANCH, body.get("branch").asText());
        assertEquals(BeautipsProjectCodexIdentity.COMMIT, body.get("commit").asText());
        assertEquals(BeautipsProjectCodexIdentity.MANIFEST_SHA256, body.get("manifestSha256").asText());
        assertEquals(
                "atenea/session-11111111-1111-4111-8111-111111111111",
                body.get("workspaceBranch").asText());
        assertNull(body.get("command"));
        assertNull(body.get("path"));
        assertNull(body.get("endpoint"));
        assertNull(body.get("environment"));
        assertEquals("ready", workspace.state());
        assertEquals(false, workspace.valuesExposed());
    }

    @Test
    void ateneaWorkspaceEnsureUsesOnlyPersistedExactIdentity() {
        AgentRunEntity run = projectRun(null);
        run.getSession().setWorkspaceBranch("atenea/session-11111111-1111-4111-8111-111111111111");

        RemoteWorkerClient.Workspace workspace = client.ensureWorkspace(run);

        JsonNode body = requestBody.get();
        assertEquals(run.getRemoteSessionId().toString(), body.get("sessionId").asText());
        assertEquals(run.getWorkspaceIdentity(), body.get("workspaceIdentity").asText());
        assertEquals(run.getRepositoryCommit(), workspace.canonicalCommit());
        assertEquals(ProjectCodexIdentity.PROJECT_IDENTITY, body.get("projectId").asText());
        assertEquals(ProjectCodexIdentity.REPOSITORY, body.get("repository").asText());
        assertEquals(ProjectCodexIdentity.BRANCH, body.get("branch").asText());
        assertEquals(TEST_CANONICAL_COMMIT, body.get("commit").asText());
        assertEquals(ProjectCodexIdentity.MANIFEST_SHA256, body.get("manifestSha256").asText());
        assertEquals("ready", workspace.state());
        assertEquals(false, workspace.valuesExposed());
    }

    @Test
    void firstProjectDispatchSendsExplicitNullThread() {
        client.dispatch(projectRun(null), "First turn.");

        assertEquals(true, requestBody.get().get("workload").get("threadId").isNull());
    }

    @Test
    void conflictingPersistedProjectFingerprintFailsBeforeNetwork() {
        AgentRunEntity run = projectRun(null);
        run.setRepositoryCommit("0".repeat(40));

        RemoteWorkerException exception = assertThrows(
                RemoteWorkerException.class,
                () -> client.dispatch(run, "Must fail."));

        assertEquals(409, exception.getStatusCode());
        assertNull(requestBody.get());
    }

    @Test
    void syntheticDispatchRemainsCompatible() {
        AgentRunEntity run = projectRun(null);
        run.setWorkloadKind("synthetic-routing-v1");
        run.setProjectIdentity(null);
        run.setRepositoryUrl(null);
        run.setRepositoryBranch(null);
        run.setRepositoryCommit(null);
        run.setManifestSha256(null);

        client.dispatch(run, "Synthetic turn.");

        JsonNode workload = requestBody.get().get("workload");
        assertEquals("synthetic-routing-v1", workload.get("kind").asText());
        assertEquals(4, workload.size());
    }

    private AgentRunEntity projectRun(String threadId) {
        UUID remoteSessionId = UUID.fromString("4bb26a65-0a0a-4ae0-b8e0-b41e03a695bf");
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(41L);
        session.setProject(project);
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setExternalThreadId(threadId);
        session.setRemoteSessionId(remoteSessionId);
        session.setCanonicalSourceRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        session.setCanonicalSourceCommit(TEST_CANONICAL_COMMIT);
        session.setCanonicalSourceObservationSha256("2".repeat(64));
        session.setCanonicalSourceObservedAt(Instant.now());
        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setDispatchId(UUID.fromString("3bb4ab61-6439-452d-a1cc-90e2eb9d9310"));
        run.setRemoteSessionId(remoteSessionId);
        run.setWorkspaceIdentity("remote:ax42-01:work-session:" + remoteSessionId);
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setLeaseGeneration(1);
        run.setStatus(AgentRunStatus.QUEUED);
        run.setWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(TEST_CANONICAL_COMMIT);
        run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
        return run;
    }

    private AgentRunEntity beautipsRun(String threadId) {
        AgentRunEntity run = projectRun(threadId);
        ProjectEntity project = run.getSession().getProject();
        project.setName(BeautipsProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(BeautipsProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.getSession().setBaseBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.setSelectedWorkerId(BeautipsProjectCodexIdentity.WORKER_ID);
        run.setProjectIdentity(BeautipsProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(BeautipsProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(BeautipsProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(BeautipsProjectCodexIdentity.COMMIT);
        run.setManifestSha256(BeautipsProjectCodexIdentity.MANIFEST_SHA256);
        return run;
    }
}
