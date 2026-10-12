package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubPullRequest;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.delivery.ChangeCompletionEvidence;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.delivery.DeliveryStore;
import com.atenea.delivery.DeliveryTarget;
import com.atenea.delivery.MobileDeliveryService;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.remote-worker.remote-close-release-enabled=true",
        "atenea.remote-worker.remote-close-project-allowlist=atenea",
        "atenea.delivery.retirement-delay-ms=600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class ChangeOwnedReleaseIntegrationTest {
    @Autowired WorkSessionService service;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired ProjectRepository projects;
    @Autowired WorkerNodeRepository workers;
    @Autowired OperatorRepository operators;
    @Autowired DeliveryStore deliveryStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired RemoteWorkerProperties workerProperties;
    @Autowired ObjectMapper mapper;
    @Autowired com.atenea.delivery.WorkSessionRetirementService retirements;
    @Autowired com.atenea.delivery.WorkSessionRetirementStore retirementStore;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockBean GitRepositoryService git;
    @MockBean GitHubClient github;
    @MockBean WorkspaceRepositoryPathValidator paths;
    @MockBean AgentRunReconciliationService runReconciliation;
    @TempDir Path temporary;

    private HttpServer worker;
    private ProjectEntity project;
    private WorkSessionEntity session;
    private DevelopmentChangeEntity change;
    private OperatorEntity administrator;
    private boolean createdWorker;
    private boolean createdProject;
    private final AtomicInteger releases = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean retainWorktree = new java.util.concurrent.atomic.AtomicBoolean(true);

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        createdWorker = false;
        createdProject = false;
        if (workers.findById(ProjectCodexIdentity.WORKER_ID).isEmpty()) {
            WorkerNodeEntity node = new WorkerNodeEntity();
            node.setId(ProjectCodexIdentity.WORKER_ID);
            node.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
            node.setEndpoint("http://127.0.0.1:1");
            node.setEnabled(true);
            node.setHealthy(true);
            node.setNormalCapacity(4);
            node.setHeavyCapacity(2);
            node.setNormalInUse(0);
            node.setHeavyInUse(0);
            node.setCapabilities(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);
            node.setLastHeartbeatAt(now);
            node.setCreatedAt(now);
            node.setUpdatedAt(now);
            workers.saveAndFlush(node);
            createdWorker = true;
        }
        project = projects.findByName("Atenea").orElseGet(() -> {
            createdProject = true;
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
        if (worker != null) { worker.stop(0); worker = null; }
        if (session != null && session.getId() != null) {
            jdbc.update("DELETE FROM work_session_retirement WHERE session_id=?", session.getId());
            jdbc.update("DELETE FROM mobile_delivery_operation WHERE session_id=?", session.getId());
            sessions.deleteById(session.getId()); session = null;
        }
        if (change != null && change.getId() != null) { changes.deleteById(change.getId()); change = null; }
        if (administrator != null) { operators.deleteById(administrator.getId()); administrator = null; }
        if (createdWorker) { workers.deleteById(ProjectCodexIdentity.WORKER_ID); createdWorker = false; }
        // This non-transactional fixture must not leave a canonical project
        // behind for a later test, or remove one it merely reused.
        if (createdProject && project != null && project.getId() != null) {
            projects.deleteById(project.getId());
            createdProject = false;
        }
    }

    @Test
    void fixtureCleanupRemovesOnlyItsOwnProjectAndKeepsUnrelatedData() {
        Long fixtureProjectId = project.getId();
        boolean ownedProject = createdProject;
        ProjectEntity unrelated = new ProjectEntity();
        unrelated.setName("Unrelated release fixture " + UUID.randomUUID());
        unrelated.setRepoPath("/synthetic/" + UUID.randomUUID());
        unrelated.setDefaultBaseBranch("main");
        unrelated.setCreatedAt(Instant.now());
        unrelated.setUpdatedAt(unrelated.getCreatedAt());
        unrelated = projects.saveAndFlush(unrelated);
        try {
            cleanUp();
            cleanUp();
            assertEquals(!ownedProject, projects.existsById(fixtureProjectId));
            assertTrue(projects.existsById(unrelated.getId()));
        } finally {
            projects.deleteById(unrelated.getId());
        }
    }

    @Test
    void fixtureCleanupPreservesAPreexistingCanonicalProject() {
        Long retainedId = project.getId();
        boolean originallyCreated = createdProject;
        // Exercise cleanup with the same ownership state as a reused project.
        createdProject = false;
        try {
            cleanUp();
            assertTrue(projects.existsById(retainedId));
        } finally {
            if (originallyCreated) projects.deleteById(retainedId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalPublishedPrWithoutCompletionIntentCannotCloseChangeOwnedSession(boolean merged) {
        when(github.getPullRequest(any(), anyLong())).thenReturn(new GitHubPullRequest(
                999999L, session.getPullRequestUrl(), "closed", merged,
                "jlnieto/atenea", "main", "jlnieto/atenea", session.getWorkspaceBranch(), session.getFinalCommitSha()));
        UUID operation = session.getRemoteCloseOperationId();
        assertEquals("DELIVERED_CHANGE_COMPLETION_REQUIRED", assertThrows(DeliveryRejectedException.class,
                () -> service.closeSession(session.getId())).code());
        WorkSessionEntity persisted = sessions.findById(session.getId()).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSING, persisted.getStatus());
        assertEquals(RemoteCloseState.BLOCKED, persisted.getRemoteCloseState());
        assertEquals(operation, persisted.getRemoteCloseOperationId());
        assertEquals(0, releases.get());
        assertTrue(deliveryStore.completion(session.getId()).isEmpty());
        verifyNoInteractions(github, runReconciliation);
        verify(git, never()).checkoutBranch(any(), any());
        verify(git, never()).deleteLocalBranch(any(), any());
        verify(git, never()).deleteRemoteBranch(any(), any());
        assertEquals(DevelopmentChangeStatus.OPEN, changes.findById(change.getId()).orElseThrow().getStatus());
    }

    @Test
    void authorizedDeliveredCompletionResumesSameRemoteCloseWithRealClientAndPostgres() throws Exception {
        authorizeDeliveredCompletionFixture();
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
        verify(paths, atLeastOnce()).normalizeConfiguredRepoPath(project.getRepoPath());
        assertEquals(change.getChangeKey().toString(), request.get().get("changeKey").asText());
        assertEquals(session.getId().longValue(), request.get().get("databaseWorkSessionId").longValue());
        assertFalse(request.get().has("manifestSha256"));
        assertFalse(request.get().has("commit"));
        assertNull(persisted.getCanonicalSourceCommit());
        assertNull(persisted.getCanonicalSourceObservationSha256());
        assertEquals(WorkSessionPullRequestStatus.MERGED, persisted.getPullRequestStatus());
        assertNotNull(persisted.getPublishedAt());
        assertEquals("b".repeat(40), persisted.getFinalCommitSha());
        assertNotNull(persisted.getIntegrationReadyAt());
        verifyNoInteractions(github);
        // Each returned response retains the normal operational snapshot;
        // close must not add the legacy shared-checkout reconciliation.
        verify(runReconciliation, times(2)).reconcileSession(session.getId());
        verifyNoMoreInteractions(runReconciliation);
        verify(git, never()).fastForwardCurrentBranchToOrigin(any(), any());
        verify(git, never()).checkoutBranch(any(), any());
        verify(git, never()).deleteLocalBranch(any(), any());
        verify(git, never()).deleteRemoteBranch(any(), any());
        DevelopmentChangeEntity retained = changes.findById(change.getId()).orElseThrow();
        assertEquals(DevelopmentChangeStatus.OPEN, retained.getStatus());
        assertEquals(DevelopmentChangeSourceState.DIRTY, retained.getSourceState());
        assertEquals(DevelopmentChangeWorkspaceState.READY, retained.getWorkspaceState());
        assertEquals(DevelopmentChangeProjectionState.CURRENT, retained.getIntegrationState());
        assertEquals("APPLYING", deliveryStore.completion(session.getId()).orElseThrow().state());
    }

    @Test
    void retirementOfDeclinedPrReleasesExactOwnerWithoutMergingAndKeepsHistory() {
        retirementFixture();
        var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
        UUID key=UUID.randomUUID();
        var first=retirements.request(session.getId(),actor,key,false);
        assertEquals(first.operationId(),retirements.request(session.getId(),actor,key,false).operationId());
        assertEquals(0,releases.get());
        retirements.reconcile();
        var op=retirementStore.find(session.getId()).orElseThrow();
        assertEquals("SUCCEEDED",op.state());
        assertEquals("ABANDONED_NOT_DELIVERED",op.evidence().path("disposition").asText());
        assertTrue(op.evidence().path("sourceAndHistoryRetained").asBoolean());
        var retired=sessions.findById(session.getId()).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED,retired.getStatus());
        assertEquals(RemoteCloseState.RELEASED,retired.getRemoteCloseState());
        assertEquals(first.operationId(),retired.getRemoteCloseOperationId());
        assertEquals(WorkSessionPullRequestStatus.DECLINED,retired.getPullRequestStatus());
        assertEquals(session.getFinalCommitSha(),retired.getFinalCommitSha());
        assertEquals(DevelopmentChangeStatus.ABANDONED,changes.findById(change.getId()).orElseThrow().getStatus());
        assertEquals(DevelopmentChangeProjectionState.NOT_STARTED,changes.findById(change.getId()).orElseThrow().getReleaseState());
        assertEquals(session.getId(),retirementStore.archivedSession(change.getChangeKey()));
        assertTrue(deliveryStore.completion(session.getId()).isEmpty());
        assertEquals(1,releases.get());
        assertEquals(first.operationId(),retirements.request(session.getId(),actor,key,false).operationId());
        retirements.reconcile(); assertEquals(1,releases.get());
        assertEquals("RETIREMENT_IDEMPOTENCY_MISMATCH",assertThrows(DeliveryRejectedException.class,
            () -> retirements.request(session.getId(),actor,UUID.randomUUID(),false)).code());
        verifyNoInteractions(github);
        verify(git,never()).checkoutBranch(any(),any()); verify(git,never()).deleteLocalBranch(any(),any());
        verify(git,never()).deleteRemoteBranch(any(),any());
    }

    @Test
    void closedOrCompletedWorkCannotBeRetired() {
        retirementFixture();
        var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
        sessions.deleteById(session.getId()); session.setId(null);
        session.setStatus(WorkSessionStatus.CLOSED); session.setClosedAt(Instant.now());
        session.setRemoteCloseState(RemoteCloseState.UNVERIFIED_LEGACY); session=sessions.saveAndFlush(session);
        assertEquals("RETIREMENT_SESSION_STATE_MISMATCH",assertThrows(DeliveryRejectedException.class,
            () -> retirements.request(session.getId(),actor,UUID.randomUUID(),false)).code());
        assertTrue(retirementStore.find(session.getId()).isEmpty()); assertEquals(0,releases.get());
    }

    @Test
    void movedOwnerBlocksSameRetirementBeforeAnyRemoteRelease() {
        retirementFixture();
        var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
        var intent=retirements.request(session.getId(),actor,UUID.randomUUID(),false);
        session.setFinalCommitSha("c".repeat(40)); sessions.saveAndFlush(session);
        retirements.reconcile();
        var op=retirementStore.find(session.getId()).orElseThrow();
        assertEquals(intent.operationId(),op.operationId()); assertEquals("BLOCKED",op.state());
        assertEquals("RETIREMENT_OWNER_MOVED",op.errorCode()); assertEquals(0,releases.get());
        assertEquals(DevelopmentChangeStatus.OPEN,changes.findById(change.getId()).orElseThrow().getStatus());
    }

    @Test
    void retirementCannotStartWithForeignOwnerOrNonAdministrator() {
        retirementFixture();
        var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
        session.setWorkspaceIdentity("remote:foreign:change:"+change.getChangeKey()); sessions.saveAndFlush(session);
        assertEquals("RETIREMENT_EXACT_OWNER_REQUIRED",assertThrows(DeliveryRejectedException.class,
            () -> retirements.request(session.getId(),actor,UUID.randomUUID(),false)).code());
        administrator.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR); operators.saveAndFlush(administrator);
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED",assertThrows(DeliveryRejectedException.class,
            () -> retirements.request(session.getId(),actor,UUID.randomUUID(),false)).code());
        assertEquals(0,releases.get());
    }

    private void retirementFixture() {
        // The base fixture intentionally starts with a BLOCKED close UUID for
        // completion tests. Retirement needs a separate not-yet-requested owner;
        // recreate this test-only row, never weaken the production monotonicity.
        sessions.deleteById(session.getId());
        session.setId(null);
        Instant now=Instant.now();
        administrator=new OperatorEntity(); administrator.setEmail(UUID.randomUUID()+"@atenea.test");
        administrator.setDisplayName("Retirement operator"); administrator.setPasswordHash("synthetic");
        administrator.setActive(true); administrator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        administrator.setCreatedAt(now); administrator.setUpdatedAt(now); administrator=operators.saveAndFlush(administrator);
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED); session.setRemoteCloseOperationId(null);
        session.setRemoteCloseRevision(0); session.setRemoteCloseRequestedAt(null); session.setRemoteCloseUpdatedAt(null);
        session.setRemoteCloseErrorCode(null); session.setPullRequestStatus(WorkSessionPullRequestStatus.DECLINED);
        session=sessions.saveAndFlush(session);
    }

    @Test
    void retirementApiRejectsCommandsAndUnexpectedArgumentsBeforeCreatingIntent() throws Exception {
        retirementFixture();
        var principal=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            new com.atenea.auth.AuthenticatedOperator(administrator.getId(),administrator.getEmail(),"Test"),null,
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR")));
        principal.setDetails(UUID.randomUUID());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
            "/api/mobile/sessions/{id}/retirement",session.getId())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(principal))
            .header("Idempotency-Key",UUID.randomUUID()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"command\":\"rm -rf workspace\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertTrue(retirementStore.find(session.getId()).isEmpty()); assertEquals(0,releases.get());
    }


    @Test
    void retirementRejectsReceiptThatDoesNotRetainWorktreeAndDoesNotMarkAbandoned() {
        retirementFixture(); retainWorktree.set(false);
        var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
        retirements.request(session.getId(),actor,UUID.randomUUID(),false);
        retirements.reconcile();
        assertEquals("BLOCKED",retirementStore.find(session.getId()).orElseThrow().state());
        assertEquals(DevelopmentChangeStatus.OPEN,changes.findById(change.getId()).orElseThrow().getStatus());
        assertNotEquals(WorkSessionStatus.CLOSED,sessions.findById(session.getId()).orElseThrow().getStatus());
        assertEquals(1,releases.get()); retirements.reconcile(); assertEquals(1,releases.get());
    }

    @Test
    void retirementAdmissionBarrierRejectsValidationUntilAndAfterRetirement() {
        retirementFixture();
        var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
        retirements.request(session.getId(),actor,UUID.randomUUID(),false);
        assertThrows(org.springframework.dao.DataAccessException.class,()->insertRunningValidation());
        retirements.reconcile();
        assertThrows(org.springframework.dao.DataAccessException.class,()->insertRunningValidation());
    }

    @Test
    void activeValidationPreventsRetirementIntent() {
        retirementFixture(); insertRunningValidation();
        try {
            var actor=new com.atenea.auth.AuthenticatedOperator(administrator.getId(),null,null);
            assertEquals("RETIREMENT_WORK_IN_PROGRESS",assertThrows(DeliveryRejectedException.class,
                ()->retirements.request(session.getId(),actor,UUID.randomUUID(),false)).code());
            assertTrue(retirementStore.find(session.getId()).isEmpty()); assertEquals(0,releases.get());
        } finally { jdbc.update("DELETE FROM validation_operation WHERE work_session_id=?",session.getId()); }
    }

    private void insertRunningValidation() {
        jdbc.update("""
            INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,
                definition_revision,identity_sha256,started_at,created_at,updated_at)
            VALUES (?,?,'BACKEND_TEST','RUNNING',?,'test-v1',?,now(),now(),now())
            """,UUID.randomUUID(),session.getId(),"5".repeat(64),UUID.randomUUID().toString().replace("-","").repeat(2));
    }

    private void authorizeDeliveredCompletionFixture() throws Exception {
        Instant now = Instant.now();
        administrator = new OperatorEntity();
        administrator.setEmail(UUID.randomUUID() + "@atenea.test");
        administrator.setDisplayName("Synthetic completion administrator");
        administrator.setPasswordHash("synthetic");
        administrator.setActive(true);
        administrator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        administrator.setCreatedAt(now); administrator.setUpdatedAt(now);
        administrator = operators.saveAndFlush(administrator);
        change.setValidationState(DevelopmentChangeProjectionState.CURRENT);
        change.setIntegrationState(DevelopmentChangeProjectionState.CURRENT);
        change = changes.saveAndFlush(change);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setPublishedChangeKey(change.getChangeKey());
        session.setPublishedSourceRevision(change.getSourceRevision());
        session.setPublishedSourceFingerprintSha256(change.getSourceFingerprintSha256());
        session.setPublishedRepository("jlnieto/atenea"); session.setPublishedBaseBranch("main");
        session.setPublishedHeadBranch(change.getWorkspaceBranch());
        session.setPublicationReceiptSha256("7".repeat(64));
        session.setSourceTreeFingerprintSha256(change.getSourceFingerprintSha256());
        session.setSourceTreeObservedAt(now);
        session.setValidationProjectionSha256("8".repeat(64));
        session.setValidationDefinitionRevision("synthetic-v1");
        session.setValidatedAt(now); session.setIntegrationReadyAt(now);
        session = sessions.saveAndFlush(session);
        String merge = "4".repeat(40);
        var integration = deliveryStore.create(session.getId(), administrator.getId(), "INTEGRATE", DeliveryTarget.APP_PROD,
                session.getFinalCommitSha(), "SUCCEEDED", mapper.createObjectNode().put("mergeCommit", merge));
        // A test-owned persisted fixture of the immutable intent produced by
        // Finalizar cambio. The real close guard/client/database remain in use.
        var binding = new ChangeCompletionEvidence("delivered-change-completion/v1", session.getId(), project.getId(),
                change.getChangeKey(), session.getRemoteSessionId(), session.getSelectedWorkerId(), session.getRemoteWorkloadKind(),
                session.getWorkspaceIdentity(), session.getWorkspaceBranch(), change.getBaseCommit(), change.getObservedCanonicalCommit(),
                change.getSourceRevision(), change.getSourceFingerprintSha256(), session.getValidationProjectionSha256(),
                session.getValidationDefinitionRevision(), session.getFinalCommitSha(), session.getPublicationReceiptSha256(),
                session.getPullRequestUrl(), integration.id(), merge);
        ObjectNode evidence = mapper.createObjectNode();
        evidence.set("completion", mapper.valueToTree(binding));
        evidence.put("completionBindingSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(binding))));
        evidence.putObject("workspaceInspection").put("disposition", "OWNED").put("workspaceDirty", false)
                .put("retainedDraft", false).put("sourceCommit", session.getFinalCommitSha())
                .put("requestFingerprintSha256", "e".repeat(64));
        evidence.set("deploymentBeforeClose", mapper.valueToTree(new MobileDeliveryService.DeploymentObservation(
                session.getId(), "DEPLOYED", "OPERATOR", "9".repeat(40), merge, true, now.getEpochSecond(),
                UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64), null)));
        deliveryStore.create(session.getId(), administrator.getId(), "FINALIZE", DeliveryTarget.APP_PROD,
                session.getFinalCommitSha(), "APPLYING", evidence);
    }

    private ObjectNode receipt(JsonNode body) throws Exception {
        ObjectNode receipt = ((ObjectNode) body).deepCopy();
        receipt.put("workerId","ax42-01");
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
                .forEach(key -> released.put(key, !body.has("changeKey")));
        ObjectNode retained = receipt.putObject("retained");
        Set.of("workspaceRecord", "worktree", "git", "turns", "agentRuns", "attachments",
                "logs", "artifacts", "backups", "policyVolumes").forEach(key -> retained.put(key, true));
        retained.put("worktree",retainWorktree.get());
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
