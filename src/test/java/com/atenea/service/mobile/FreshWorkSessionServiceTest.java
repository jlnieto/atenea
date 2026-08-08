package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.mobile.StartFreshWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.ReviewedInstructionBundleIdentity;
import com.atenea.service.worksession.AgentRunService;
import com.atenea.service.worksession.WorkSessionService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class FreshWorkSessionServiceTest {

    @Mock OperatorRepository operatorRepository;
    @Mock WorkSessionRepository workSessionRepository;
    @Mock AgentRunRepository agentRunRepository;
    @Mock AgentRunService agentRunService;
    @Mock RemoteWorkerClient remoteWorkerClient;
    @Mock WorkSessionService workSessionService;
    @Mock PlatformTransactionManager transactionManager;

    private final RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    private final List<String> events = new ArrayList<>();
    private FreshWorkSessionService service;
    private WorkSessionEntity source;
    private WorkSessionEntity successor;
    private AgentRunEntity run;

    @BeforeEach
    void setUp() {
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(status);

        RemoteWorkerProperties properties = new RemoteWorkerProperties();
        properties.setFreshSessionOnSourceAdvanceEnabled(true);
        properties.setFreshSessionProjectAllowlist(Set.of("atenea"));
        service = new FreshWorkSessionService(
                jdbc,
                operatorRepository,
                workSessionRepository,
                agentRunRepository,
                agentRunService,
                properties,
                remoteWorkerClient,
                workSessionService,
                transactionManager);

        ProjectEntity project = new ProjectEntity();
        project.setId(1L);
        project.setName(ProjectCodexIdentity.PROJECT_NAME);
        project.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        source = new WorkSessionEntity();
        source.setId(17L);
        source.setProject(project);
        source.setTitle("Atenea");
        source.setStatus(WorkSessionStatus.OPEN);
        source.setBaseBranch(ProjectCodexIdentity.BRANCH);
        source.setCanonicalSourceRef("refs/heads/main");
        source.setCanonicalSourceCommit("1".repeat(40));
        source.setCanonicalSourceObservationSha256("2".repeat(64));
        source.setCanonicalSourceObservedAt(Instant.parse("2026-08-08T10:00:00Z"));

        successor = new WorkSessionEntity();
        successor.setId(18L);
        successor.setProject(project);
        successor.setStatus(WorkSessionStatus.OPEN);

        run = new AgentRunEntity();
        run.setId(96L);
        run.setSession(source);
        run.setStatus(AgentRunStatus.FAILED);
        run.setRemoteSessionId(UUID.fromString("18c00753-6080-42f7-ac05-18c47b236cac"));
        run.setWorkspaceIdentity("remote:ax42-01:work-session:" + run.getRemoteSessionId());
        run.setWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        run.setProjectIdentity(ProjectCodexIdentity.PROJECT_IDENTITY);
        run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);
        run.setRepositoryBranch(ProjectCodexIdentity.BRANCH);
        run.setRepositoryCommit(source.getCanonicalSourceCommit());
        run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
        ReviewedInstructionBundleIdentity.apply(run, ProjectCodexIdentity.PROJECT_IDENTITY);

        OperatorEntity operator = new OperatorEntity();
        operator.setId(1L);
        operator.setActive(true);
        operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        when(operatorRepository.findByIdForRecoveryRequest(1L))
                .thenReturn(Optional.of(operator));
        when(workSessionRepository.findLockedWithProjectById(17L))
                .thenReturn(Optional.of(source));
        when(workSessionRepository.findWithProjectById(17L))
                .thenReturn(Optional.of(source));
        when(workSessionRepository.findWithProjectById(18L))
                .thenReturn(Optional.of(successor));
        when(agentRunRepository.findFirstBySessionIdOrderByCreatedAtDesc(17L))
                .thenReturn(Optional.of(run));
        when(agentRunService.isRemoteRetryEligible(96L)).thenReturn(true);
        when(remoteWorkerClient.diagnoseWorkspaceReadiness(run)).thenReturn(
                new RemoteWorkerClient.WorkspaceReadiness(
                        "project-workspace-readiness-v1",
                        "SOURCE_ADVANCED",
                        run.getRemoteSessionId().toString(),
                        run.getWorkspaceIdentity(),
                        "atenea",
                        "ax42-01",
                        "1".repeat(40),
                        "3".repeat(40),
                        false,
                        "START_FRESH_SESSION",
                        "4".repeat(64),
                        "5".repeat(64),
                        false));

        lenient().doAnswer(ignored -> {
            events.add("close-source");
            source.setStatus(WorkSessionStatus.CLOSED);
            source.setRemoteCloseState(RemoteCloseState.RELEASED);
            source.setRemoteCloseReceiptSha256("6".repeat(64));
            source.setRemoteCloseReleasedAt(Instant.parse("2026-08-08T10:01:00Z"));
            return null;
        }).when(workSessionService).closeSession(17L);
        WorkSessionConversationViewResponse view = mock(WorkSessionConversationViewResponse.class);
        WorkSessionViewResponse nested = mock(WorkSessionViewResponse.class);
        WorkSessionResponse sessionResponse = mock(WorkSessionResponse.class);
        when(view.view()).thenReturn(nested);
        when(nested.session()).thenReturn(sessionResponse);
        when(sessionResponse.id()).thenReturn(18L);
        lenient().when(workSessionService.resolveFreshSessionConversationView(
                any(), any(), any())).thenAnswer(invocation -> {
            events.add("resolve-successor");
            UUID operationId = invocation.getArgument(2);
            successor.setFreshStartOperationId(operationId);
            return new ResolveWorkSessionConversationViewResponse(true, view);
        });
        when(workSessionService.getSessionConversationView(18L)).thenReturn(view);
    }

    @Test
    void persistsReleaseBeforeOneEmptySuccessorAndRepeatsSameResult() {
        UUID key = UUID.fromString("00000000-0000-4000-8000-000000000017");
        AuthenticatedOperator operator = new AuthenticatedOperator(
                1L, "operator@example.invalid", "Operator");

        var first = service.start(operator, 17L, new StartFreshWorkSessionRequest(key));
        var repeated = service.start(operator, 17L, new StartFreshWorkSessionRequest(key));

        assertEquals("COMPLETED", first.state());
        assertEquals(17L, first.sourceWorkSessionId());
        assertEquals(18L, first.resultWorkSessionId());
        assertTrue(first.created());
        assertEquals(first.operationId(), repeated.operationId());
        assertEquals(first.resultWorkSessionId(), repeated.resultWorkSessionId());
        assertFalse(repeated.created());
        assertEquals(List.of(
                "persist-request", "close-source", "persist-source-released",
                "resolve-successor", "persist-completed"), events);
        verify(workSessionService, times(1)).closeSession(17L);
        verify(workSessionService, times(1)).resolveFreshSessionConversationView(
                1L, "Atenea", first.operationId());
    }

    @Test
    void resumesSameOperationWhenResponseIsLostAfterSourceRelease() {
        UUID key = UUID.fromString("00000000-0000-4000-8000-000000000117");
        AuthenticatedOperator operator = new AuthenticatedOperator(
                1L, "operator@example.invalid", "Operator");
        int[] attempts = {0};
        doAnswer(ignored -> {
            events.add("close-source");
            source.setStatus(WorkSessionStatus.CLOSED);
            source.setRemoteCloseState(RemoteCloseState.RELEASED);
            source.setRemoteCloseReceiptSha256("6".repeat(64));
            if (attempts[0]++ == 0) {
                throw new IllegalStateException("synthetic-response-loss");
            }
            return null;
        }).when(workSessionService).closeSession(17L);

        assertThrows(IllegalStateException.class, () ->
                service.start(operator, 17L, new StartFreshWorkSessionRequest(key)));
        var resumed = service.start(
                operator, 17L, new StartFreshWorkSessionRequest(key));

        assertEquals("COMPLETED", resumed.state());
        assertEquals(18L, resumed.resultWorkSessionId());
        assertEquals(List.of(
                "persist-request", "close-source", "close-source",
                "persist-source-released", "resolve-successor",
                "persist-completed"), events);
        verify(workSessionService, times(2)).closeSession(17L);
        verify(workSessionService, times(1)).resolveFreshSessionConversationView(
                1L, "Atenea", resumed.operationId());
    }

    @Test
    void resumesSameOperationWhenBackendStopsBeforeSuccessorCreation() {
        UUID key = UUID.fromString("00000000-0000-4000-8000-000000000217");
        AuthenticatedOperator operator = new AuthenticatedOperator(
                1L, "operator@example.invalid", "Operator");
        int[] attempts = {0};
        doAnswer(invocation -> {
            events.add("resolve-successor");
            if (attempts[0]++ == 0) {
                throw new IllegalStateException("synthetic-backend-stop");
            }
            UUID operationId = invocation.getArgument(2);
            successor.setFreshStartOperationId(operationId);
            WorkSessionConversationViewResponse view =
                    workSessionService.getSessionConversationView(18L);
            return new ResolveWorkSessionConversationViewResponse(true, view);
        }).when(workSessionService).resolveFreshSessionConversationView(
                any(), any(), any());

        assertThrows(IllegalStateException.class, () ->
                service.start(operator, 17L, new StartFreshWorkSessionRequest(key)));
        var resumed = service.start(
                operator, 17L, new StartFreshWorkSessionRequest(key));

        assertEquals("COMPLETED", resumed.state());
        assertEquals(18L, resumed.resultWorkSessionId());
        assertEquals(List.of(
                "persist-request", "close-source", "persist-source-released",
                "resolve-successor", "resolve-successor",
                "persist-completed"), events);
        verify(workSessionService, times(1)).closeSession(17L);
        verify(workSessionService, times(2)).resolveFreshSessionConversationView(
                1L, "Atenea", resumed.operationId());
    }

    private final class RecordingJdbcTemplate extends JdbcTemplate {
        private OperationRow row;

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO fresh_work_session_operation")) {
                row = new OperationRow(
                        (UUID) args[0],
                        (UUID) args[1],
                        (Long) args[3],
                        (Long) args[4],
                        (Long) args[5],
                        "REQUESTED",
                        null);
                events.add("persist-request");
            } else if (sql.contains("state = 'COMPLETED'")) {
                row.state = "COMPLETED";
                row.resultSessionId = (Long) args[0];
                events.add("persist-completed");
            } else if (sql.contains("state = 'SOURCE_RELEASED'")) {
                row.state = "SOURCE_RELEASED";
                events.add("persist-source-released");
            }
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (row == null || !matches(sql, args)) {
                return List.of();
            }
            ResultSet result = mock(ResultSet.class);
            try {
                when(result.getObject("operation_id", UUID.class)).thenReturn(row.operationId);
                when(result.getObject("idempotency_key", UUID.class)).thenReturn(row.idempotencyKey);
                when(result.getLong("operator_id")).thenReturn(row.operatorId);
                when(result.getLong("source_work_session_id")).thenReturn(row.sourceSessionId);
                when(result.getLong("source_agent_run_id")).thenReturn(row.sourceAgentRunId);
                when(result.getString("state")).thenReturn(row.state);
                when(result.getObject("result_work_session_id", Long.class))
                        .thenReturn(row.resultSessionId);
                return List.of(mapper.mapRow(result, 0));
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private boolean matches(String sql, Object[] args) {
            if (sql.contains("operator_id = ? AND idempotency_key = ?")) {
                return row.operatorId.equals(args[0]) && row.idempotencyKey.equals(args[1]);
            }
            if (sql.contains("source_work_session_id = ?")) {
                return row.sourceSessionId.equals(args[0]);
            }
            return row.operationId.equals(args[0]);
        }
    }

    private static final class OperationRow {
        private final UUID operationId;
        private final UUID idempotencyKey;
        private final Long operatorId;
        private final Long sourceSessionId;
        private final Long sourceAgentRunId;
        private String state;
        private Long resultSessionId;

        private OperationRow(
                UUID operationId,
                UUID idempotencyKey,
                Long operatorId,
                Long sourceSessionId,
                Long sourceAgentRunId,
                String state,
                Long resultSessionId
        ) {
            this.operationId = operationId;
            this.idempotencyKey = idempotencyKey;
            this.operatorId = operatorId;
            this.sourceSessionId = sourceSessionId;
            this.sourceAgentRunId = sourceAgentRunId;
            this.state = state;
            this.resultSessionId = resultSessionId;
        }
    }
}
