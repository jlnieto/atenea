package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.atenea.api.developmentchange.CreateDevelopmentChangeRequest;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateEntity;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyEntity;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceCommand;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceGateway;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceObservation;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.git.GitRepositoryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

@SpringBootTest(properties = {
        "atenea.development-change.mutations-enabled=true",
        "atenea.development-change.session-binding-enabled=true",
        "atenea.development-change.workspace-operations-enabled=true",
        "atenea.development-change.workspace-reconciliation-enabled=true",
        "atenea.remote-worker.enabled=true",
        "atenea.remote-worker.worker-id=ax42-01"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = DevelopmentChangeTwoChangeIsolationIntegrationTest
                .IsolatedDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class DevelopmentChangeTwoChangeIsolationIntegrationTest {

    private static final String DATABASE = "atenea_m2_22_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String BASE_DATABASE_URL =
            requiredEnvironment("SPRING_DATASOURCE_URL");
    private static final String ISOLATED_DATABASE_URL =
            replaceDatabase(BASE_DATABASE_URL, DATABASE);
    private static final long POLICY_REVISION = 22;

    @TempDir Path temporaryDirectory;

    @Autowired private DevelopmentChangeService changeService;
    @Autowired private DevelopmentChangeWorkspaceService workspaceService;
    @Autowired private DevelopmentChangeProperties properties;
    @Autowired private RemoteWorkerProperties remoteWorkerProperties;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private DevelopmentChangeRepository changeRepository;
    @Autowired private DevelopmentChangeOperationRepository changeOperationRepository;
    @Autowired private DevelopmentChangeWorkspaceOperationRepository workspaceOperationRepository;
    @Autowired private WorkSessionRepository workSessionRepository;
    @Autowired private SessionTurnRepository sessionTurnRepository;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private WorkerNodeRepository workerNodeRepository;
    @Autowired private V2GlobalCapabilityGateRepository globalGateRepository;
    @Autowired private V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    @MockBean private GitRepositoryService gitRepositoryService;
    @MockBean private CanonicalSourceAdmissionService canonicalSourceAdmissionService;
    @MockBean private DevelopmentChangeWorkspaceGateway gateway;

    private ProjectEntity project;
    private AuthenticatedOperator actor;
    private SyntheticWorkspaceWorker syntheticWorker;
    private String baseCommit;
    private String baseTree;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        createIsolatedDatabase();
        registry.add("spring.datasource.url", () -> ISOLATED_DATABASE_URL);
    }

    @BeforeEach
    void setUp() throws Exception {
        reset(gitRepositoryService, gateway);
        properties.setMutationsEnabled(true);
        properties.setSessionBindingEnabled(true);
        properties.setWorkspaceOperationsEnabled(true);
        properties.setWorkspaceReconciliationEnabled(true);
        remoteWorkerProperties.setEnabled(true);
        remoteWorkerProperties.setWorkerId(ProjectCodexIdentity.WORKER_ID);

        Path sourceRepository = initializeSyntheticRepository();
        baseCommit = run(sourceRepository, "git", "rev-parse", "HEAD");
        baseTree = run(sourceRepository, "git", "rev-parse", "HEAD^{tree}");
        syntheticWorker = new SyntheticWorkspaceWorker(
                sourceRepository,
                Files.createDirectory(temporaryDirectory.resolve("worktrees")),
                Files.createDirectory(temporaryDirectory.resolve("resources")));
        when(gateway.execute(any())).thenAnswer(invocation ->
                syntheticWorker.execute(invocation.getArgument(0)));

        project = exactAteneaProject();
        registerWorker();
        enablePolicy();
        OperatorEntity operator = operatorRepository.saveAndFlush(operator());
        actor = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());

        when(canonicalSourceAdmissionService.observeCanonicalSource(any(ProjectEntity.class)))
                .thenReturn(new CanonicalSourceAdmissionService.CanonicalSourceObservation(
                        ProjectCodexIdentity.REPOSITORY,
                        "refs/heads/" + ProjectCodexIdentity.BRANCH,
                        baseCommit,
                        "9".repeat(64),
                        Instant.now()));
        when(gitRepositoryService.resolveCommitTree(
                ProjectCodexIdentity.REPO_PATH, baseCommit))
                .thenReturn(baseTree);
        when(gitRepositoryService.exactLocalHeadExists(
                eq(ProjectCodexIdentity.REPO_PATH), anyString()))
                .thenReturn(false);
    }

    @Test
    void provesTwoChangesKeepBranchesWorktreesSessionsThreadsAndResourcesIsolated()
            throws Exception {
        DevelopmentChangeEntity first = createAndProvision("Synthetic change A");
        DevelopmentChangeEntity second = createAndProvision("Synthetic change B");
        SyntheticAllocation firstAllocation = syntheticWorker.allocation(first.getChangeKey());
        SyntheticAllocation secondAllocation = syntheticWorker.allocation(second.getChangeKey());

        assertNotEquals(first.getChangeKey(), second.getChangeKey());
        assertNotEquals(first.getWorkspaceBranch(), second.getWorkspaceBranch());
        assertNotEquals(first.getWorkspaceIdentity(), second.getWorkspaceIdentity());
        assertNotEquals(firstAllocation.worktree(), secondAllocation.worktree());
        assertNotEquals(firstAllocation.resourcePath(), secondAllocation.resourcePath());
        assertNotEquals(firstAllocation.remoteSessionId(), secondAllocation.remoteSessionId());
        assertNotEquals(firstAllocation.codexThreadId(), secondAllocation.codexThreadId());
        assertEquals(first.getWorkspaceBranch(),
                run(firstAllocation.worktree(), "git", "branch", "--show-current"));
        assertEquals(second.getWorkspaceBranch(),
                run(secondAllocation.worktree(), "git", "branch", "--show-current"));
        assertEquals(baseCommit,
                run(firstAllocation.worktree(), "git", "rev-parse", "HEAD"));
        assertEquals(baseCommit,
                run(secondAllocation.worktree(), "git", "rev-parse", "HEAD"));
        assertTrue(Files.isRegularFile(firstAllocation.resourcePath().resolve("owner.txt")));
        assertTrue(Files.isRegularFile(secondAllocation.resourcePath().resolve("owner.txt")));

        Files.writeString(firstAllocation.worktree().resolve("only-first.txt"),
                "synthetic isolation proof\n", StandardCharsets.UTF_8);
        assertFalse(Files.exists(secondAllocation.worktree().resolve("only-first.txt")));

        WorkSessionEntity firstSession = session(first, firstAllocation);
        changeService.bindSession(
                actor, project.getId(), first.getChangeKey(), firstSession.getId(),
                UUID.randomUUID());
        retainDraft(firstSession.getId());

        WorkSessionEntity secondSession = session(second, secondAllocation);
        DevelopmentChangeRejectedException crossChange = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> changeService.bindSession(
                        actor, project.getId(), first.getChangeKey(), secondSession.getId(),
                        UUID.randomUUID()));
        assertEquals("DEVELOPMENT_CHANGE_SESSION_OWNERSHIP_MISMATCH",
                crossChange.response().failureCode());
        changeService.bindSession(
                actor, project.getId(), second.getChangeKey(), secondSession.getId(),
                UUID.randomUUID());

        WorkSessionEntity storedFirst = workSessionRepository
                .findWithProjectAndDevelopmentChangeById(firstSession.getId())
                .orElseThrow();
        WorkSessionEntity storedSecond = workSessionRepository
                .findWithProjectAndDevelopmentChangeById(secondSession.getId())
                .orElseThrow();
        assertEquals(first.getId(), storedFirst.getDevelopmentChange().getId());
        assertEquals(second.getId(), storedSecond.getDevelopmentChange().getId());
        assertEquals(firstAllocation.codexThreadId(), storedFirst.getExternalThreadId());
        assertEquals(secondAllocation.codexThreadId(), storedSecond.getExternalThreadId());
        assertEquals(firstAllocation.remoteSessionId(), storedFirst.getRemoteSessionId());
        assertEquals(secondAllocation.remoteSessionId(), storedSecond.getRemoteSessionId());
        assertNotEquals(storedFirst.getWorkspaceIdentity(), storedSecond.getWorkspaceIdentity());
        assertEquals(2, changeRepository.count());
        assertEquals(2, workSessionRepository.count());
        assertEquals(4, changeOperationRepository.count());
        assertEquals(2, workspaceOperationRepository.count());
        assertEquals(0, sessionTurnRepository.count());
        assertEquals(0, agentRunRepository.count());
        assertEquals(2, syntheticWorker.provisionCount());
        assertEquals(0, syntheticWorker.promptCount());
    }

    private DevelopmentChangeEntity createAndProvision(String title) {
        var created = changeService.create(
                actor,
                project.getId(),
                UUID.randomUUID(),
                new CreateDevelopmentChangeRequest(title));
        var provisioned = workspaceService.provision(
                actor,
                project.getId(),
                created.developmentChange().changeKey(),
                UUID.randomUUID());
        assertEquals(DevelopmentChangeWorkspaceOperationState.SUCCEEDED,
                provisioned.state());
        assertEquals(DevelopmentChangeWorkspaceState.READY,
                provisioned.workspaceState());
        return changeRepository.findByChangeKey(
                created.developmentChange().changeKey()).orElseThrow();
    }

    private WorkSessionEntity session(
            DevelopmentChangeEntity change,
            SyntheticAllocation allocation) {
        Instant now = Instant.now();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Synthetic isolated session");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setExternalThreadId(allocation.codexThreadId());
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setRemoteSessionId(allocation.remoteSessionId());
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return workSessionRepository.saveAndFlush(session);
    }

    private void retainDraft(Long sessionId) {
        WorkSessionEntity session = workSessionRepository
                .findWithProjectAndDevelopmentChangeById(sessionId)
                .orElseThrow();
        Instant now = Instant.now();
        session.setStatus(WorkSessionStatus.DRAFT_BLOCKED);
        session.setDraftFingerprintSha256(sha256("retained", session.getId()));
        session.setDraftRetainedHead(baseCommit);
        session.setDraftStagedChangeCount(0);
        session.setDraftUnstagedChangeCount(0);
        session.setDraftUntrackedChangeCount(1);
        session.setDraftBlockedAt(now);
        session.setLastActivityAt(now);
        session.setUpdatedAt(now);
        workSessionRepository.saveAndFlush(session);
    }

    private Path initializeSyntheticRepository() throws Exception {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("source"));
        run(repository, "git", "init", "--initial-branch=main");
        run(repository, "git", "config", "user.name", "Synthetic M2 Test");
        run(repository, "git", "config", "user.email", "synthetic-m2@atenea.test");
        Files.writeString(repository.resolve("fixture.txt"), "synthetic\n",
                StandardCharsets.UTF_8);
        run(repository, "git", "add", "fixture.txt");
        run(repository, "git", "commit", "-m", "synthetic two-change fixture");
        return repository;
    }

    private ProjectEntity exactAteneaProject() {
        Instant now = Instant.now();
        ProjectEntity value = new ProjectEntity();
        value.setName(ProjectCodexIdentity.PROJECT_NAME);
        value.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        value.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return projectRepository.saveAndFlush(value);
    }

    private OperatorEntity operator() {
        Instant now = Instant.now();
        OperatorEntity value = new OperatorEntity();
        value.setEmail("synthetic-m2-22@atenea.test");
        value.setDisplayName("Synthetic M2 operator");
        value.setPasswordHash("synthetic-non-secret-hash");
        value.setActive(true);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return value;
    }

    private void registerWorker() {
        Instant now = Instant.now();
        WorkerNodeEntity worker = new WorkerNodeEntity();
        worker.setId(ProjectCodexIdentity.WORKER_ID);
        worker.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
        worker.setEndpoint("http://127.0.0.1:1");
        worker.setEnabled(true);
        worker.setHealthy(true);
        worker.setNormalCapacity(2);
        worker.setHeavyCapacity(0);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setCapabilities(ProjectCodexIdentity.WORKLOAD_KIND);
        worker.setLastHeartbeatAt(now);
        worker.setCreatedAt(now);
        worker.setUpdatedAt(now);
        workerNodeRepository.saveAndFlush(worker);
    }

    private void enablePolicy() {
        Instant now = Instant.now();
        V2GlobalCapabilityGateEntity global = new V2GlobalCapabilityGateEntity();
        global.setCapability(DevelopmentChangePolicy.CAPABILITY);
        global.setEnabled(true);
        global.setRevision(1);
        global.setCreatedAt(now);
        global.setUpdatedAt(now);
        globalGateRepository.saveAndFlush(global);

        V2ProjectCapabilityPolicyEntity exact = new V2ProjectCapabilityPolicyEntity();
        exact.setProjectId(project.getId());
        exact.setCapability(DevelopmentChangePolicy.CAPABILITY);
        exact.setEnabled(true);
        exact.setPolicyRevision(POLICY_REVISION);
        exact.setCreatedAt(now);
        exact.setUpdatedAt(now);
        projectPolicyRepository.saveAndFlush(exact);
    }

    private static String run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command))
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("Synthetic git command timed out");
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Synthetic git command failed: " + output);
        }
        return output;
    }

    private static String sha256(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private final class SyntheticWorkspaceWorker {

        private final Path sourceRepository;
        private final Path worktreeRoot;
        private final Path resourceRoot;
        private final Map<UUID, SyntheticAllocation> allocations = new LinkedHashMap<>();
        private int provisions;

        private SyntheticWorkspaceWorker(
                Path sourceRepository,
                Path worktreeRoot,
                Path resourceRoot) {
            this.sourceRepository = sourceRepository;
            this.worktreeRoot = worktreeRoot;
            this.resourceRoot = resourceRoot;
        }

        private DevelopmentChangeWorkspaceObservation execute(
                DevelopmentChangeWorkspaceCommand command) throws Exception {
            if (command.operationKind()
                    != DevelopmentChangeWorkspaceOperationKind.PROVISION
                    || !DevelopmentChangeWorkspaceCommand.CREATE_EFFECT.equals(
                            command.effect())) {
                throw new IllegalArgumentException("Synthetic proof accepts provisioning only");
            }
            SyntheticAllocation allocation = allocations.get(command.changeKey());
            if (allocation == null) {
                Path worktree = worktreeRoot.resolve(command.changeKey().toString());
                Path resource = resourceRoot.resolve(command.changeKey().toString());
                run(sourceRepository, "git", "worktree", "add", "-b",
                        command.workspaceBranch(), worktree.toString(), command.baseCommit());
                Files.createDirectory(resource);
                Files.writeString(resource.resolve("owner.txt"),
                        command.workspaceIdentity() + "\n", StandardCharsets.UTF_8);
                allocation = new SyntheticAllocation(
                        worktree,
                        resource,
                        UUID.nameUUIDFromBytes(("session:" + command.changeKey())
                                .getBytes(StandardCharsets.UTF_8)),
                        "synthetic-thread-" + command.changeKey());
                allocations.put(command.changeKey(), allocation);
                provisions++;
            }
            return new DevelopmentChangeWorkspaceObservation(
                    DevelopmentChangeWorkspaceObservation.Disposition.OWNED,
                    command.expectedCanonicalCommit(),
                    command.sourceFingerprintSha256(),
                    false,
                    false,
                    sha256(command.operationId(), command.idempotencyKey()),
                    sha256(command.workspaceIdentity(), allocation.worktree(),
                            allocation.resourcePath()));
        }

        private SyntheticAllocation allocation(UUID changeKey) {
            return java.util.Optional.ofNullable(allocations.get(changeKey)).orElseThrow();
        }

        private int provisionCount() {
            return provisions;
        }

        private int promptCount() {
            return 0;
        }
    }

    private record SyntheticAllocation(
            Path worktree,
            Path resourcePath,
            UUID remoteSessionId,
            String codexThreadId) {
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void createIsolatedDatabase() {
        try (Connection connection = DriverManager.getConnection(
                        BASE_DATABASE_URL,
                        requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                        requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + DATABASE + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to create isolated two-change test database", exception);
        }
    }

    private static String replaceDatabase(String jdbcUrl, String database) {
        int queryIndex = jdbcUrl.indexOf('?');
        String base = queryIndex >= 0 ? jdbcUrl.substring(0, queryIndex) : jdbcUrl;
        String query = queryIndex >= 0 ? jdbcUrl.substring(queryIndex) : "";
        int slash = base.lastIndexOf('/');
        if (!base.startsWith("jdbc:postgresql://")
                || slash <= "jdbc:postgresql://".length()
                || slash == base.length() - 1) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_URL is not an exact PostgreSQL database URL");
        }
        return base.substring(0, slash + 1) + database + query;
    }

    static final class IsolatedDatabaseCleanupListener
            extends AbstractTestExecutionListener {

        @Override
        public int getOrder() {
            return DirtiesContextTestExecutionListener.ORDER - 1;
        }

        @Override
        public void afterTestClass(TestContext testContext) throws Exception {
            try (Connection connection = DriverManager.getConnection(
                            BASE_DATABASE_URL,
                            requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                            requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "DROP DATABASE IF EXISTS \"" + DATABASE + "\" WITH (FORCE)");
            }
        }
    }
}
