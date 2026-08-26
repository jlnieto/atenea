package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.atenea.api.developmentchange.CreateDevelopmentChangeRequest;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationKind;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateEntity;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyEntity;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.service.git.GitRepositoryService;
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
        "atenea.remote-worker.worker-id=synthetic-worker-01"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        listeners = DevelopmentChangeConcurrencyIntegrationTest.IsolatedDatabaseCleanupListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class DevelopmentChangeConcurrencyIntegrationTest {

    private static final String DATABASE =
            "atenea_m2_13_"
                    + UUID.randomUUID().toString().replace("-", "");
    private static final String BASE_DATABASE_URL =
            requiredEnvironment("SPRING_DATASOURCE_URL");
    private static final String ISOLATED_DATABASE_URL =
            replaceDatabase(BASE_DATABASE_URL, DATABASE);

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        createIsolatedDatabase();
        registry.add("spring.datasource.url", () -> ISOLATED_DATABASE_URL);
    }

    @Autowired private DevelopmentChangeService service;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private DevelopmentChangeRepository changeRepository;
    @Autowired private DevelopmentChangeOperationRepository operationRepository;
    @Autowired private V2GlobalCapabilityGateRepository globalGateRepository;
    @Autowired private V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    @MockBean private GitRepositoryService gitRepositoryService;
    @MockBean private CanonicalSourceAdmissionService canonicalSourceAdmissionService;

    @Test
    void concurrentIdenticalCreateHasOneDurableWinnerAndOneReplay() throws Exception {
        String identity = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ProjectEntity project = new ProjectEntity();
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        project.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project = projectRepository.saveAndFlush(project);

        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(identity + "@atenea.test");
        operator.setDisplayName("Synthetic concurrent operator");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        operator = operatorRepository.saveAndFlush(operator);

        V2GlobalCapabilityGateEntity global = globalGateRepository
                .findById(DevelopmentChangePolicy.CAPABILITY)
                .orElseGet(V2GlobalCapabilityGateEntity::new);
        if (global.getCapability() == null) {
            global.setCapability(DevelopmentChangePolicy.CAPABILITY);
            global.setCreatedAt(now);
        }
        global.setEnabled(true);
        global.setRevision(Math.max(1, global.getRevision()));
        global.setUpdatedAt(now);
        globalGateRepository.saveAndFlush(global);

        V2ProjectCapabilityPolicyEntity exact = new V2ProjectCapabilityPolicyEntity();
        exact.setProjectId(project.getId());
        exact.setCapability(DevelopmentChangePolicy.CAPABILITY);
        exact.setEnabled(true);
        exact.setPolicyRevision(11);
        exact.setCreatedAt(now);
        exact.setUpdatedAt(now);
        projectPolicyRepository.saveAndFlush(exact);

        String commit = "3".repeat(40);
        when(canonicalSourceAdmissionService.observeCanonicalSource(any(ProjectEntity.class)))
                .thenReturn(new CanonicalSourceAdmissionService.CanonicalSourceObservation(
                        ProjectCodexIdentity.REPOSITORY,
                        "refs/heads/" + ProjectCodexIdentity.BRANCH,
                        commit,
                        "5".repeat(64),
                        now));
        when(gitRepositoryService.resolveCommitTree(project.getRepoPath(), commit))
                .thenReturn("4".repeat(40));
        when(gitRepositoryService.exactLocalHeadExists(eq(project.getRepoPath()), anyString()))
                .thenReturn(false);

        AuthenticatedOperator actor = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
        UUID idempotencyKey = UUID.randomUUID();
        Long projectId = project.getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.create(actor, projectId, idempotencyKey,
                        new CreateDevelopmentChangeRequest("Concurrent synthetic change"));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.create(actor, projectId, idempotencyKey,
                        new CreateDevelopmentChangeRequest("Concurrent synthetic change"));
            });
            ready.await();
            start.countDown();

            var left = first.get();
            var right = second.get();
            assertEquals(left.operationId(), right.operationId());
            assertEquals(left.receiptSha256(), right.receiptSha256());
            assertEquals(left.developmentChange().changeKey(),
                    right.developmentChange().changeKey());
            assertTrue(left.replayed() ^ right.replayed());
            assertFalse(left.receiptSha256().isBlank());
        }

        assertEquals(1, changeRepository
                .findAllByProjectIdOrderByUpdatedAtDescIdDesc(projectId).size());
        assertTrue(operationRepository
                .findByOperatorIdAndOperationKindAndIdempotencyKey(
                        operator.getId(), DevelopmentChangeOperationKind.CREATE,
                        idempotencyKey)
                .isPresent());
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
            throw new IllegalStateException("SPRING_DATASOURCE_URL is not an exact PostgreSQL database URL");
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
                statement.execute(
                        "DROP DATABASE IF EXISTS \"" + DATABASE + "\" WITH (FORCE)");
            }
        }
    }
}
