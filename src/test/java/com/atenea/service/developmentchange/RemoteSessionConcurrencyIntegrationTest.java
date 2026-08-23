package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.atenea.api.developmentchange.OpenOrResolveRemoteSessionRequest;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.developmentchange.RemoteSessionOperationKind;
import com.atenea.persistence.developmentchange.RemoteSessionOperationRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateEntity;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyEntity;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.v2control.V2AuditFact;
import com.atenea.service.v2control.V2AuditOutboxService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
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
        "atenea.remote-work-beta.open-or-resolve-enabled=true",
        "atenea.remote-work-beta.recovery-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = RemoteSessionConcurrencyIntegrationTest.IsolatedDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class RemoteSessionConcurrencyIntegrationTest {

    private static final String DATABASE = "atenea_m25_remote_session_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String BASE_DATABASE_URL = requiredEnvironment("SPRING_DATASOURCE_URL");
    private static final String ISOLATED_DATABASE_URL = replaceDatabase(BASE_DATABASE_URL, DATABASE);

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        createIsolatedDatabase();
        registry.add("spring.datasource.url", () -> ISOLATED_DATABASE_URL);
    }

    @Autowired private RemoteSessionService service;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private DevelopmentChangeRepository changeRepository;
    @Autowired private RemoteSessionOperationRepository operationRepository;
    @Autowired private WorkSessionRepository workSessionRepository;
    @Autowired private WorkerNodeRepository workerNodeRepository;
    @Autowired private V2GlobalCapabilityGateRepository globalGateRepository;
    @Autowired private V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    @MockBean private V2AuditOutboxService auditService;

    @Test
    void concurrentExactRetriesHaveOneAtomicWinnerAndOneReplay() throws Exception {
        Fixture fixture = fixture();
        UUID idempotencyKey = UUID.randomUUID();
        long expectedRevision = fixture.change().getVersion();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> invokeAfterSignal(
                    fixture, idempotencyKey, expectedRevision, ready, start));
            var second = executor.submit(() -> invokeAfterSignal(
                    fixture, idempotencyKey, expectedRevision, ready, start));
            ready.await();
            start.countDown();

            var left = first.get();
            var right = second.get();
            assertEquals(left.operationId(), right.operationId());
            assertEquals(left.receiptSha256(), right.receiptSha256());
            assertEquals(left.sessionId(), right.sessionId());
            assertTrue(left.replayed() ^ right.replayed());
        }

        assertEquals(1, workSessionRepository
                .findAllByDevelopmentChangeIdOrderByOpenedAtAscIdAsc(fixture.change().getId())
                .size());
        assertEquals(1, operationRepository.count());
    }

    @Test
    void auditFailureRollsBackOperationSessionAndChangeRevisionTogether() {
        Fixture fixture = fixture();
        UUID idempotencyKey = UUID.randomUUID();
        long expectedRevision = fixture.change().getVersion();
        doThrow(new IllegalStateException("synthetic transactional audit failure"))
                .when(auditService).record(any(V2AuditFact.class));

        assertThrows(IllegalStateException.class, () -> service.openOrResolve(
                fixture.actor(), fixture.project().getId(), fixture.change().getChangeKey(),
                idempotencyKey, new OpenOrResolveRemoteSessionRequest(expectedRevision)));

        assertTrue(operationRepository
                .findByOperatorIdAndOperationKindAndIdempotencyKey(
                        fixture.actor().operatorId(), RemoteSessionOperationKind.OPEN_OR_RESOLVE_REMOTE_SESSION,
                        idempotencyKey)
                .isEmpty());
        assertTrue(workSessionRepository
                .findAllByDevelopmentChangeIdOrderByOpenedAtAscIdAsc(fixture.change().getId())
                .isEmpty());
        assertEquals(expectedRevision,
                changeRepository.findById(fixture.change().getId()).orElseThrow().getVersion());
    }

    private com.atenea.api.developmentchange.RemoteSessionOperationResponse invokeAfterSignal(
            Fixture fixture,
            UUID idempotencyKey,
            long expectedRevision,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return service.openOrResolve(
                fixture.actor(), fixture.project().getId(), fixture.change().getChangeKey(),
                idempotencyKey, new OpenOrResolveRemoteSessionRequest(expectedRevision));
    }

    private Fixture fixture() {
        String identity = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ProjectEntity project = new ProjectEntity();
        project.setName("m25-remote-session-" + identity);
        project.setRepoPath("/tmp/m25-remote-session-" + identity);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project = projectRepository.saveAndFlush(project);

        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(identity + "@atenea.test");
        operator.setDisplayName("Synthetic M2.5 concurrent operator");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        operator = operatorRepository.saveAndFlush(operator);

        enablePolicy(DevelopmentChangePolicy.CAPABILITY, project, 7, now);
        enablePolicy(RemoteWorkBetaPolicy.CAPABILITY, project, 3, now);
        registerWorker(now);

        UUID changeKey = UUID.randomUUID();
        DevelopmentChangeEntity change = new DevelopmentChangeEntity();
        change.setChangeKey(changeKey);
        change.setProject(project);
        change.setTitle("Synthetic concurrent remote session");
        change.setStatus(DevelopmentChangeStatus.OPEN);
        change.setBaseRef("refs/heads/main");
        change.setBaseCommit("1".repeat(40));
        change.setWorkspaceBranch("atenea/change-" + changeKey);
        change.setWorkspaceIdentity("remote:synthetic-worker-01:change:" + changeKey);
        change.setSelectedWorkerId("synthetic-worker-01");
        change.setProjectPolicyRevision(7);
        change.setSourceRevision(0);
        change.setSourceFingerprintSha256("a".repeat(64));
        change.setSourceState(DevelopmentChangeSourceState.CLEAN);
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(1);
        change.setWorkspaceObservationSha256("b".repeat(64));
        change.setWorkspaceUpdatedAt(now);
        change.setValidationState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setReviewState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setIntegrationState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setReleaseState(DevelopmentChangeProjectionState.NOT_STARTED);
        change.setCreatedAt(now);
        change.setUpdatedAt(now);
        change = changeRepository.saveAndFlush(change);

        return new Fixture(project, change, new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName()));
    }

    private void registerWorker(Instant now) {
        if (workerNodeRepository.existsById("synthetic-worker-01")) {
            return;
        }
        WorkerNodeEntity worker = new WorkerNodeEntity();
        worker.setId("synthetic-worker-01");
        worker.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
        worker.setEndpoint("http://127.0.0.1:1");
        worker.setEnabled(true);
        worker.setHealthy(true);
        worker.setNormalCapacity(4);
        worker.setHeavyCapacity(2);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setCapabilities(ProjectCodexIdentity.WORKLOAD_KIND);
        worker.setLastHeartbeatAt(now);
        worker.setCreatedAt(now);
        worker.setUpdatedAt(now);
        workerNodeRepository.saveAndFlush(worker);
    }

    private void enablePolicy(
            String capability,
            ProjectEntity project,
            long revision,
            Instant now) {
        V2GlobalCapabilityGateEntity global = globalGateRepository.findById(capability)
                .orElseGet(V2GlobalCapabilityGateEntity::new);
        if (global.getCapability() == null) {
            global.setCapability(capability);
            global.setCreatedAt(now);
        }
        global.setEnabled(true);
        global.setRevision(Math.max(1, global.getRevision()));
        global.setUpdatedAt(now);
        globalGateRepository.saveAndFlush(global);

        V2ProjectCapabilityPolicyEntity exact = new V2ProjectCapabilityPolicyEntity();
        exact.setProjectId(project.getId());
        exact.setCapability(capability);
        exact.setEnabled(true);
        exact.setPolicyRevision(revision);
        exact.setCreatedAt(now);
        exact.setUpdatedAt(now);
        projectPolicyRepository.saveAndFlush(exact);
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
            throw new IllegalStateException("Unable to create isolated test database", exception);
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

    static final class IsolatedDatabaseCleanupListener extends AbstractTestExecutionListener {

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
                statement.execute("DROP DATABASE IF EXISTS \"" + DATABASE + "\" WITH (FORCE)");
            }
        }
    }

    private record Fixture(
            ProjectEntity project,
            DevelopmentChangeEntity change,
            AuthenticatedOperator actor) {}
}
