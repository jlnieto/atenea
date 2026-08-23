package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
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
import com.atenea.remoteworker.DevelopmentChangeWorkspaceCommand;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceGateway;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceObservation;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerProperties;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
        "atenea.development-change.workspace-operations-enabled=true",
        "atenea.development-change.workspace-reconciliation-enabled=true",
        "atenea.remote-worker.enabled=true",
        "atenea.remote-worker.worker-id=ax42-01"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = DevelopmentChangeWorkspaceServiceIntegrationTest
                .IsolatedDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class DevelopmentChangeWorkspaceServiceIntegrationTest {

    private static final String DATABASE = "atenea_m2_21_"
            + UUID.randomUUID().toString().replace("-", "");
    private static final String BASE_DATABASE_URL =
            requiredEnvironment("SPRING_DATASOURCE_URL");
    private static final String ISOLATED_DATABASE_URL =
            replaceDatabase(BASE_DATABASE_URL, DATABASE);
    private static final long POLICY_REVISION = 11;
    private static final String BASE_COMMIT = "1".repeat(40);
    private static final String SOURCE_FINGERPRINT = "a".repeat(64);

    @Autowired private DevelopmentChangeWorkspaceService service;
    @Autowired private DevelopmentChangeProperties properties;
    @Autowired private RemoteWorkerProperties remoteWorkerProperties;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private DevelopmentChangeRepository changeRepository;
    @Autowired private DevelopmentChangeWorkspaceOperationRepository operationRepository;
    @Autowired private V2GlobalCapabilityGateRepository globalGateRepository;
    @Autowired private V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    @MockBean private DevelopmentChangeWorkspaceGateway gateway;

    private ProjectEntity project;
    private OperatorEntity operator;
    private AuthenticatedOperator actor;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        createIsolatedDatabase();
        registry.add("spring.datasource.url", () -> ISOLATED_DATABASE_URL);
    }

    @BeforeEach
    void setUp() {
        reset(gateway);
        properties.setMutationsEnabled(true);
        properties.setWorkspaceOperationsEnabled(true);
        properties.setWorkspaceReconciliationEnabled(true);
        remoteWorkerProperties.setEnabled(true);
        remoteWorkerProperties.setWorkerId(ProjectCodexIdentity.WORKER_ID);
        project = exactProject();
        enablePolicy();
        operator = operatorRepository.saveAndFlush(operator());
        actor = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
    }

    @Test
    void provisionsExactOwnedWorkspaceAndReplaysWithoutAnotherRemoteEffect() {
        DevelopmentChangeEntity change = change();
        doReturn(owned(BASE_COMMIT, SOURCE_FINGERPRINT, false))
                .when(gateway).execute(any());
        UUID key = UUID.randomUUID();

        var first = service.provision(actor, project.getId(), change.getChangeKey(), key);
        var replay = service.provision(actor, project.getId(), change.getChangeKey(), key);

        assertEquals(DevelopmentChangeWorkspaceOperationState.SUCCEEDED, first.state());
        assertEquals(DevelopmentChangeWorkspaceState.READY, first.workspaceState());
        assertNotNull(first.receiptSha256());
        assertTrue(replay.replayed());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.receiptSha256(), replay.receiptSha256());
        verify(gateway, times(1)).execute(any());
    }

    @Test
    void lostResponseRemainsUncertainUntilExactReconciliationWins() {
        DevelopmentChangeEntity change = change();
        when(gateway.execute(any())).thenThrow(
                new RemoteWorkerException("synthetic lost response", new IOException("lost")));

        var uncertain = service.provision(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID());

        assertEquals(DevelopmentChangeWorkspaceOperationState.UNCERTAIN, uncertain.state());
        assertEquals(DevelopmentChangeWorkspaceState.UNCERTAIN, uncertain.workspaceState());
        assertEquals("RECONCILE_WORKSPACE", uncertain.nextAction().kind().name());

        doReturn(owned(BASE_COMMIT, SOURCE_FINGERPRINT, false))
                .when(gateway).execute(any());
        var reconciled = service.reconcile(actor, project.getId(), change.getChangeKey());

        assertEquals(DevelopmentChangeWorkspaceOperationKind.RECONCILE,
                reconciled.operationKind());
        assertEquals(DevelopmentChangeWorkspaceOperationState.SUCCEEDED,
                reconciled.state());
        assertEquals(DevelopmentChangeWorkspaceState.READY, reconciled.workspaceState());
        var successor = operationRepository.findByOperationId(
                reconciled.operationId()).orElseThrow();
        assertEquals(uncertain.operationId(), successor.getPredecessorOperationId());
        verify(gateway, times(2)).execute(any());
    }

    @Test
    void restartConvertsPersistedDispatchToUncertainAndReconcilesWithoutReprovisioning() {
        DevelopmentChangeEntity change = change();
        when(gateway.execute(any())).thenThrow(new AssertionError("synthetic process stop"));

        assertThrows(AssertionError.class, () -> service.provision(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID()));
        var dispatched = operationRepository
                .findAllByStateInOrderByRequestedAtAsc(
                        java.util.Set.of(DevelopmentChangeWorkspaceOperationState.DISPATCHED))
                .stream()
                .filter(operation -> operation.getDevelopmentChange().getId().equals(change.getId()))
                .findFirst()
                .orElseThrow();

        reset(gateway);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            DevelopmentChangeWorkspaceCommand command = invocation.getArgument(0);
            assertEquals(DevelopmentChangeWorkspaceOperationKind.RECONCILE,
                    command.operationKind());
            assertEquals(dispatched.getOperationId(), command.predecessorOperationId());
            return owned(BASE_COMMIT, SOURCE_FINGERPRINT, false);
        });

        assertTrue(service.reconcilePersistedAfterStartup() >= 1);

        DevelopmentChangeEntity reconciled = changeRepository
                .findByChangeKey(change.getChangeKey()).orElseThrow();
        assertEquals(DevelopmentChangeWorkspaceState.READY,
                reconciled.getWorkspaceState());
        assertEquals(DevelopmentChangeWorkspaceOperationState.UNCERTAIN,
                operationRepository.findByOperationId(
                        dispatched.getOperationId()).orElseThrow().getState());
        verify(gateway, times(1)).execute(any());
    }

    @Test
    void ownershipMismatchIsTerminalAndNeverAdopted() {
        DevelopmentChangeEntity change = change();
        when(gateway.execute(any())).thenThrow(new RemoteWorkerException(
                "synthetic ownership mismatch",
                409,
                "DEVELOPMENT_CHANGE_WORKER_OWNERSHIP_MISMATCH",
                com.atenea.remoteworker.RemoteWorkerFailureCategory.OWNERSHIP,
                false,
                com.atenea.persistence.worksession.AgentRunRecoveryNextAction
                        .CONTACT_PLATFORM_ADMINISTRATOR,
                null));

        var result = service.provision(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID());

        assertEquals(DevelopmentChangeWorkspaceOperationState.BLOCKED, result.state());
        assertEquals(DevelopmentChangeWorkspaceState.BLOCKED, result.workspaceState());
        assertEquals("DEVELOPMENT_CHANGE_WORKER_OWNERSHIP_MISMATCH",
                result.failureCode());
        assertEquals("RESOLVE_OWNERSHIP", result.nextAction().kind().name());
    }

    @Test
    void foreignResourceIsRefusedWithoutReplacementOrSecondCall() {
        DevelopmentChangeEntity change = change();
        when(gateway.execute(any())).thenReturn(foreign());

        var result = service.provision(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID());

        assertEquals(DevelopmentChangeWorkspaceOperationState.BLOCKED, result.state());
        assertEquals("DEVELOPMENT_CHANGE_FOREIGN_RESOURCE_REFUSED",
                result.failureCode());
        assertEquals(BASE_COMMIT, changeRepository.findByChangeKey(
                change.getChangeKey()).orElseThrow().getBaseCommit());
        verify(gateway, times(1)).execute(any());
    }

    @Test
    void canonicalAdvanceRetainsBaseAndMarksCurrentEvidenceStale() {
        DevelopmentChangeEntity change = change();
        change.setValidationState(DevelopmentChangeProjectionState.CURRENT);
        change.setReviewState(DevelopmentChangeProjectionState.CURRENT);
        changeRepository.saveAndFlush(change);
        String advanced = "2".repeat(40);
        String changedFingerprint = "b".repeat(64);
        when(gateway.execute(any())).thenReturn(
                owned(advanced, changedFingerprint, true));

        var result = service.provision(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID());

        assertEquals(DevelopmentChangeSourceState.STALE, result.sourceState());
        DevelopmentChangeEntity stored = changeRepository
                .findByChangeKey(change.getChangeKey()).orElseThrow();
        assertEquals(BASE_COMMIT, stored.getBaseCommit());
        assertEquals(advanced, stored.getObservedCanonicalCommit());
        assertEquals(DevelopmentChangeProjectionState.STALE,
                stored.getValidationState());
        assertEquals(DevelopmentChangeProjectionState.STALE, stored.getReviewState());
    }

    @Test
    void disabledOrNonAteneaTargetFailsBeforeAnyWorkerCall() {
        DevelopmentChangeEntity change = change();
        properties.setWorkspaceOperationsEnabled(false);

        DevelopmentChangeRejectedException disabled = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.provision(
                        actor, project.getId(), change.getChangeKey(), UUID.randomUUID()));
        assertEquals("DEVELOPMENT_CHANGE_WORKSPACE_OPERATIONS_DISABLED",
                disabled.response().failureCode());
        verify(gateway, never()).execute(any());

        properties.setWorkspaceOperationsEnabled(true);
        project.setRepoPath("/foreign/repository");
        project.setUpdatedAt(Instant.now());
        projectRepository.saveAndFlush(project);
        DevelopmentChangeRejectedException foreignProject = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.inspect(
                        actor, project.getId(), change.getChangeKey(), UUID.randomUUID()));
        assertEquals("DEVELOPMENT_CHANGE_WORKSPACE_POLICY_DRIFT",
                foreignProject.response().failureCode());
        verify(gateway, never()).execute(any());
    }

    private ProjectEntity exactProject() {
        ProjectEntity existing = projectRepository
                .findByName(ProjectCodexIdentity.PROJECT_NAME).orElse(null);
        if (existing != null) {
            existing.setRepoPath(ProjectCodexIdentity.REPO_PATH);
            existing.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
            existing.setUpdatedAt(Instant.now());
            return projectRepository.saveAndFlush(existing);
        }
        ProjectEntity value = new ProjectEntity();
        value.setName(ProjectCodexIdentity.PROJECT_NAME);
        value.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        value.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        value.setCreatedAt(Instant.now());
        value.setUpdatedAt(value.getCreatedAt());
        return projectRepository.saveAndFlush(value);
    }

    private void enablePolicy() {
        Instant now = Instant.now();
        V2GlobalCapabilityGateEntity global = globalGateRepository
                .findById(DevelopmentChangePolicy.CAPABILITY).orElse(null);
        if (global == null) {
            global = new V2GlobalCapabilityGateEntity();
            global.setCapability(DevelopmentChangePolicy.CAPABILITY);
            global.setCreatedAt(now);
        }
        global.setEnabled(true);
        global.setRevision(1);
        global.setUpdatedAt(now);
        globalGateRepository.saveAndFlush(global);

        V2ProjectCapabilityPolicyEntity exact = projectPolicyRepository
                .findByProjectIdAndCapability(project.getId(), DevelopmentChangePolicy.CAPABILITY)
                .orElse(null);
        if (exact == null) {
            exact = new V2ProjectCapabilityPolicyEntity();
            exact.setProjectId(project.getId());
            exact.setCapability(DevelopmentChangePolicy.CAPABILITY);
            exact.setCreatedAt(now);
        }
        exact.setEnabled(true);
        exact.setPolicyRevision(POLICY_REVISION);
        exact.setUpdatedAt(now);
        projectPolicyRepository.saveAndFlush(exact);
    }

    private OperatorEntity operator() {
        Instant now = Instant.now();
        OperatorEntity value = new OperatorEntity();
        value.setEmail(UUID.randomUUID() + "@m2-workspace.test");
        value.setDisplayName("Synthetic M2 workspace operator");
        value.setPasswordHash("synthetic-hash");
        value.setActive(true);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return value;
    }

    private DevelopmentChangeEntity change() {
        UUID key = UUID.randomUUID();
        Instant now = Instant.now();
        DevelopmentChangeEntity value = new DevelopmentChangeEntity();
        value.setChangeKey(key);
        value.setProject(project);
        value.setTitle("Synthetic durable workspace " + key);
        value.setStatus(DevelopmentChangeStatus.OPEN);
        value.setBaseRef("refs/heads/" + ProjectCodexIdentity.BRANCH);
        value.setBaseCommit(BASE_COMMIT);
        value.setWorkspaceBranch("atenea/change-" + key);
        value.setWorkspaceIdentity("remote:" + ProjectCodexIdentity.WORKER_ID
                + ":change:" + key);
        value.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        value.setProjectPolicyRevision(POLICY_REVISION);
        value.setSourceRevision(0);
        value.setSourceFingerprintSha256(SOURCE_FINGERPRINT);
        value.setSourceState(DevelopmentChangeSourceState.CLEAN);
        value.setWorkspaceState(DevelopmentChangeWorkspaceState.NOT_PROVISIONED);
        value.setWorkspaceOperationRevision(0);
        value.setValidationState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setReviewState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setIntegrationState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setReleaseState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return changeRepository.saveAndFlush(value);
    }

    private DevelopmentChangeWorkspaceObservation owned(
            String canonicalCommit,
            String sourceFingerprint,
            boolean dirty) {
        return new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.OWNED,
                canonicalCommit,
                sourceFingerprint,
                dirty,
                dirty,
                "c".repeat(64),
                "d".repeat(64));
    }

    private DevelopmentChangeWorkspaceObservation foreign() {
        return new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.FOREIGN,
                null,
                null,
                false,
                false,
                "c".repeat(64),
                "d".repeat(64));
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
                    "Unable to create isolated workspace-operation test database",
                    exception);
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
