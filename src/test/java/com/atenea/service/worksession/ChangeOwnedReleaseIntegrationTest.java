package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.remote-worker.remote-close-release-enabled=true",
        "atenea.remote-worker.remote-close-project-allowlist=atenea"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChangeOwnedReleaseIntegrationTest {
    @Autowired WorkSessionService service;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired ProjectRepository projects;
    @Autowired RemoteWorkerProperties workerProperties;
    @Autowired ObjectMapper mapper;
    @MockBean GitRepositoryService git;
    @MockBean GitHubClient github;
    @MockBean WorkspaceRepositoryPathValidator paths;
    @MockBean AgentRunReconciliationService runReconciliation;
    @TempDir Path temporary;

    private HttpServer worker;
    private WorkSessionEntity session;
    private DevelopmentChangeEntity change;
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        ProjectEntity project = projects.findByName("Atenea").orElseGet(() -> {
            ProjectEntity created = new ProjectEntity();
            created.setName("Atenea");
            created.setRepoPath("/repos/atenea");
            created.setDefaultBaseBranch("main");
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return projects.saveAndFlush(created);
        });
        change = new DevelopmentChangeEntity();
        change.setChangeKey(UUID.randomUUID());
        change.setProject(project);
        change.setTitle("Synthetic change release");
        change.setBaseRef("refs/heads/main");
        change.setBaseCommit("1".repeat(40));
        change.setWorkspaceIdentity("remote:ax42-01:change:" + change.getChangeKey());
        change.setWorkspaceBranch("atenea/change-" + change.getChangeKey());
        change.setSelectedWorkerId("ax42-01");
        change.setProjectPolicyRevision(1);
        change.setSourceFingerprintSha256("a".repeat(64));
        change.setSourceState(DevelopmentChangeSourceState.DIRTY);
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(1);
        change.setWorkspaceObservationSha256("c".repeat(64));
        change.setWorkspaceOwnershipFingerprintSha256("d".repeat(64));
        change.setWorkspaceUpdatedAt(now);
        change.setObservedCanonicalCommit(change.getBaseCommit());
        change.setCreatedAt(now);
        change.setUpdatedAt(now);
        change = changes.saveAndFlush(change);
        session = new WorkSessionEntity();
        session.setProject(project);
        session.setDevelopmentChange(change);
        session.setTitle("Synthetic change-owned close");
        session.setStatus(WorkSessionStatus.CLOSING);
        session.setBaseBranch("main");
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01");
        session.setRemoteSessionId(UUID.randomUUID());
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.BLOCKED);
        session.setRemoteCloseOperationId(UUID.randomUUID());
        session.setRemoteCloseRevision(2);
        session.setRemoteCloseRequestedAt(now);
        session.setRemoteCloseUpdatedAt(now);
        session.setRemoteCloseErrorCode("REMOTE_WORKER_PROTOCOL_FAILURE");
        session.setPublishedAt(now);
        session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/999999");
        session.setPullRequestStatus(WorkSessionPullRequestStatus.OPEN);
        session.setFinalCommitSha("b".repeat(40));
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session = sessions.saveAndFlush(session);
        when(paths.normalizeNullableText(any())).thenCallRealMethod();
        when(paths.normalizeConfiguredRepoPath("/repos/atenea")).thenReturn("/synthetic/atenea");
        when(git.getCurrentBranch(anyString())).thenReturn("main");
        when(git.isWorkingTreeClean(anyString())).thenReturn(true);
        when(git.getOriginRemoteUrl(anyString())).thenReturn(ProjectCodexIdentity.REPOSITORY);
        when(github.resolveRepository(any())).thenReturn(new GitHubRepositoryRef("jlnieto", "atenea"));
        when(github.extractPullRequestNumber(any())).thenReturn(999999L);
        worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker.createContext("/v1/project-workspaces/release", exchange -> {
            try {
                releases.incrementAndGet();
                JsonNode body = mapper.readTree(exchange.getRequestBody());
                request.set(body);
                assertEquals(body.get("operationId").asText(),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                byte[] response = mapper.writeValueAsBytes(receipt(body));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            } finally {
                exchange.close();
            }
        });
        worker.start();
        Path token = temporary.resolve("synthetic-token");
        Files.writeString(token, "t".repeat(64));
        workerProperties.setEndpoint("http://127.0.0.1:" + worker.getAddress().getPort());
        workerProperties.setTokenFile(token.toString());
    }

    @AfterEach
    void cleanUp() {
        if (worker != null) worker.stop(0);
        if (session != null && session.getId() != null) sessions.deleteById(session.getId());
        if (change != null && change.getId() != null) changes.deleteById(change.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalPublishedPrResumesClosingWithRealClientAndPostgres(boolean merged) {
        when(github.getPullRequest(any(), anyLong())).thenReturn(new GitHubPullRequest(
                999999L, session.getPullRequestUrl(), "closed", merged,
                "jlnieto/atenea", "main", "jlnieto/atenea", session.getWorkspaceBranch(), session.getFinalCommitSha()));
        UUID operation = session.getRemoteCloseOperationId();
        var response = service.closeSession(session.getId());
        var replay = service.closeSession(session.getId());
        WorkSessionEntity persisted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED, response.status());
        assertEquals(RemoteCloseState.RELEASED, response.remoteCloseState());
        assertEquals(response.id(), replay.id());
        assertEquals(response.status(), replay.status());
        assertEquals(response.remoteCloseState(), replay.remoteCloseState());
        assertEquals(response.pullRequestStatus(), replay.pullRequestStatus());
        assertEquals(operation, persisted.getRemoteCloseOperationId());
        assertEquals(6, persisted.getRemoteCloseRevision());
        assertEquals(1, releases.get());
        verify(paths, atLeastOnce()).normalizeConfiguredRepoPath("/repos/atenea");
        assertEquals(change.getChangeKey().toString(), request.get().get("changeKey").asText());
        assertEquals(session.getId().longValue(), request.get().get("databaseWorkSessionId").longValue());
        assertFalse(request.get().has("manifestSha256"));
        assertFalse(request.get().has("commit"));
        assertNull(persisted.getCanonicalSourceCommit());
        assertNull(persisted.getCanonicalSourceObservationSha256());
        assertEquals(merged ? WorkSessionPullRequestStatus.MERGED : WorkSessionPullRequestStatus.DECLINED,
                persisted.getPullRequestStatus());
        assertNotNull(persisted.getPublishedAt());
        assertEquals("b".repeat(40), persisted.getFinalCommitSha());
        if (!merged) {
            assertNull(persisted.getIntegrationReadyAt());
            verify(git, never()).fastForwardCurrentBranchToOrigin(any(), any());
            verify(git, never()).deleteLocalBranch(any(), any());
            verify(git, never()).deleteRemoteBranch(any(), any());
        }
        DevelopmentChangeEntity retained = changes.findById(change.getId()).orElseThrow();
        assertEquals(DevelopmentChangeStatus.OPEN, retained.getStatus());
        assertEquals(DevelopmentChangeSourceState.DIRTY, retained.getSourceState());
        assertEquals(DevelopmentChangeWorkspaceState.READY, retained.getWorkspaceState());
        assertEquals(DevelopmentChangeProjectionState.NOT_STARTED, retained.getIntegrationState());
    }

    private ObjectNode receipt(JsonNode body) throws Exception {
        ObjectNode receipt = ((ObjectNode) body).deepCopy();
        receipt.put("schemaVersion", "project-workspace-release-v1");
        receipt.put("state", "RELEASED");
        receipt.put("revision", 6);
        receipt.put("requestFingerprintSha256", hash(body));
        receipt.put("ownershipFingerprintSha256", "c".repeat(64));
        ObjectNode removed = receipt.putObject("removed");
        Set.of("runtimeContainers", "runtimeNetworks", "sessionImages", "previewResources",
                "brokerResources", "browserProcesses").forEach(key -> removed.put(key, 0));
        ObjectNode released = receipt.putObject("released");
        Set.of("registration", "normalAdmission", "heavyAdmission", "allocation")
                .forEach(key -> released.put(key, false));
        ObjectNode retained = receipt.putObject("retained");
        Set.of("workspaceRecord", "worktree", "git", "turns", "agentRuns", "attachments",
                "logs", "artifacts", "backups", "policyVolumes").forEach(key -> retained.put(key, true));
        receipt.put("valuesExposed", false);
        receipt.put("receiptSha256", hash(receipt));
        return receipt;
    }

    private String hash(JsonNode node) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(canonical(node))));
    }

    private Object canonical(JsonNode node) {
        if (!node.isObject()) return mapper.convertValue(node, Object.class);
        Map<String, Object> sorted = new TreeMap<>();
        node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
        return sorted;
    }
}
