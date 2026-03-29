package com.atenea.api.worksession;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.api.ApiExceptionHandler;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.worksession.AgentRunAlreadyRunningException;
import com.atenea.service.worksession.OpenWorkSessionAlreadyExistsException;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionNotOpenException;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import com.atenea.service.worksession.WorkSessionCloseBlockedException;
import com.atenea.service.worksession.WorkSessionPublishConflictException;
import com.atenea.service.worksession.WorkSessionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class WorkSessionControllerTest {

    @Mock
    private WorkSessionService workSessionService;

    @Mock
    private WorkSessionGitHubService workSessionGitHubService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkSessionController(workSessionService, workSessionGitHubService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().build()))
                .build();
    }

    @Test
    void openSessionReturnsCreatedSession() throws Exception {
        when(workSessionService.openSession(7L, new CreateWorkSessionRequest("Inspect project state", "main")))
                .thenReturn(sessionResponse(WorkSessionStatus.OPEN, WorkSessionOperationalState.IDLE, null, null, null));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state",
                                  "baseBranch": "main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.pullRequestStatus").value("NOT_CREATED"));
    }

    @Test
    void openSessionReturnsBadRequestWhenTitleIsBlank() throws Exception {
        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void openSessionReturnsConflictWhenOpenSessionAlreadyExists() throws Exception {
        when(workSessionService.openSession(7L, new CreateWorkSessionRequest("Inspect project state", null)))
                .thenThrow(new OpenWorkSessionAlreadyExistsException(7L));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void openSessionReturnsUnprocessableEntityWhenRepoIsNotOperational() throws Exception {
        when(workSessionService.openSession(7L, new CreateWorkSessionRequest("Inspect project state", null)))
                .thenThrow(new WorkSessionOperationBlockedException("Repository is not operational"));

        mockMvc.perform(post("/api/projects/7/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Inspect project state"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Repository is not operational"));
    }

    @Test
    void resolveSessionReturnsExistingOpenSession() throws Exception {
        when(workSessionService.resolveSession(7L, new ResolveWorkSessionRequest("Ignored title", "main")))
                .thenReturn(new ResolveWorkSessionResponse(
                        false,
                        sessionResponse(WorkSessionStatus.OPEN, WorkSessionOperationalState.IDLE, "thread-1", null, null)));

        mockMvc.perform(post("/api/projects/7/sessions/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Ignored title",
                                  "baseBranch": "main"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.session.externalThreadId").value("thread-1"));
    }

    @Test
    void resolveSessionReturnsBadRequestWhenNoOpenSessionExistsAndTitleIsMissing() throws Exception {
        when(workSessionService.resolveSession(7L, new ResolveWorkSessionRequest(null, null)))
                .thenThrow(new IllegalArgumentException("Session title is required when no open WorkSession exists"));

        mockMvc.perform(post("/api/projects/7/sessions/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Session title is required when no open WorkSession exists"));
    }

    @Test
    void getSessionReturnsSession() throws Exception {
        when(workSessionService.getSession(12L)).thenReturn(sessionResponse(
                WorkSessionStatus.OPEN,
                WorkSessionOperationalState.RUNNING,
                "thread-1",
                null,
                null,
                new SessionOperationalSnapshotResponse(true, false, "feature/docs", true)));

        mockMvc.perform(get("/api/sessions/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationalState").value("RUNNING"))
                .andExpect(jsonPath("$.repoState.currentBranch").value("feature/docs"));
    }

    @Test
    void getSessionReturnsCloseBlockStateWhenSessionIsClosing() throws Exception {
        when(workSessionService.getSession(12L)).thenReturn(sessionResponse(
                WorkSessionStatus.CLOSING,
                WorkSessionOperationalState.CLOSING,
                "thread-1",
                null,
                null,
                "dirty_worktree",
                "Repository working tree is not clean",
                "Clean or discard local changes manually before retrying close",
                false,
                new SessionOperationalSnapshotResponse(true, false, "atenea/session-12", false)));

        mockMvc.perform(get("/api/sessions/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSING"))
                .andExpect(jsonPath("$.operationalState").value("CLOSING"))
                .andExpect(jsonPath("$.closeBlockedState").value("dirty_worktree"))
                .andExpect(jsonPath("$.closeBlockedReason").value("Repository working tree is not clean"))
                .andExpect(jsonPath("$.closeBlockedAction").value(
                        "Clean or discard local changes manually before retrying close"))
                .andExpect(jsonPath("$.closeRetryable").value(false));
    }

    @Test
    void getSessionReturnsNotFoundWhenSessionDoesNotExist() throws Exception {
        when(workSessionService.getSession(12L)).thenThrow(new WorkSessionNotFoundException(12L));

        mockMvc.perform(get("/api/sessions/12"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSessionViewReturnsAggregatedSessionState() throws Exception {
        when(workSessionService.getSessionView(12L)).thenReturn(new WorkSessionViewResponse(
                sessionResponse(WorkSessionStatus.OPEN, WorkSessionOperationalState.IDLE, "thread-1", null, null),
                false,
                true,
                new WorkSessionViewLatestRunResponse(
                        55L,
                        AgentRunStatus.SUCCEEDED,
                        101L,
                        102L,
                        "turn-1",
                        Instant.parse("2026-03-25T10:05:01Z"),
                        Instant.parse("2026-03-25T10:05:02Z"),
                        "Current status summary",
                        null),
                null,
                "Current status summary"));

        mockMvc.perform(get("/api/sessions/12/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.id").value(12))
                .andExpect(jsonPath("$.latestRun.id").value(55));
    }

    @Test
    void getSessionConversationViewReturnsConversationReadyPayload() throws Exception {
        when(workSessionService.getSessionConversationView(12L)).thenReturn(new WorkSessionConversationViewResponse(
                new WorkSessionViewResponse(
                        sessionResponse(WorkSessionStatus.OPEN, WorkSessionOperationalState.IDLE, "thread-1", null, null),
                        false,
                        true,
                        null,
                        null,
                        "Current status summary"),
                List.of(
                        new SessionTurnResponse(101L, com.atenea.persistence.worksession.SessionTurnActor.OPERATOR,
                                "Inspect project", Instant.parse("2026-03-25T10:05:00Z")),
                        new SessionTurnResponse(102L, com.atenea.persistence.worksession.SessionTurnActor.CODEX,
                                "Current status summary", Instant.parse("2026-03-25T10:06:00Z"))),
                20,
                false));

        mockMvc.perform(get("/api/sessions/12/conversation-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.session.id").value(12))
                .andExpect(jsonPath("$.recentTurns[1].actor").value("CODEX"));
    }

    @Test
    void resolveSessionConversationViewReturnsProjectConversationReadyPayload() throws Exception {
        when(workSessionService.resolveSessionConversationView(7L, new ResolveWorkSessionRequest("Ignored title", "main")))
                .thenReturn(new ResolveWorkSessionConversationViewResponse(
                        false,
                        new WorkSessionConversationViewResponse(
                                new WorkSessionViewResponse(
                                        sessionResponse(WorkSessionStatus.OPEN, WorkSessionOperationalState.IDLE, "thread-1", null, null),
                                        false,
                                        true,
                                        null,
                                        null,
                                        "Current status summary"),
                                List.of(new SessionTurnResponse(
                                        101L,
                                        com.atenea.persistence.worksession.SessionTurnActor.OPERATOR,
                                        "Inspect project",
                                        Instant.parse("2026-03-25T10:05:00Z"))),
                                20,
                                false)));

        mockMvc.perform(post("/api/projects/7/sessions/resolve/conversation-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Ignored title",
                                  "baseBranch": "main"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.id").value(12));
    }

    @Test
    void closeSessionReturnsClosedSession() throws Exception {
        when(workSessionService.closeSession(12L)).thenReturn(sessionResponse(
                WorkSessionStatus.CLOSED,
                WorkSessionOperationalState.CLOSED,
                "thread-1",
                null,
                Instant.parse("2026-03-25T10:07:00Z")));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closeSessionConversationViewReturnsClosedConversationState() throws Exception {
        when(workSessionService.closeSessionConversationView(12L)).thenReturn(new CloseWorkSessionConversationViewResponse(
                new WorkSessionConversationViewResponse(
                        new WorkSessionViewResponse(
                                sessionResponse(
                                        WorkSessionStatus.CLOSED,
                                        WorkSessionOperationalState.CLOSED,
                                        "thread-1",
                                        null,
                                        Instant.parse("2026-03-25T10:07:00Z")),
                                false,
                                false,
                                null,
                                null,
                                "Current status summary"),
                        List.of(),
                        20,
                        false)));

        mockMvc.perform(post("/api/sessions/12/close/conversation-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.status").value("CLOSED"));
    }

    @Test
    void closeSessionReturnsConflictWhenRunIsRunning() throws Exception {
        when(workSessionService.closeSession(12L)).thenThrow(new AgentRunAlreadyRunningException(12L));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isConflict());
    }

    @Test
    void closeSessionReturnsConflictWhenReconciliationBlocksClosing() throws Exception {
        when(workSessionService.closeSession(12L)).thenThrow(new WorkSessionCloseBlockedException(
                "WorkSession '12' cannot finish closing: Repository working tree is not clean",
                "dirty_worktree",
                "Repository working tree is not clean",
                "Clean or discard local changes manually before retrying close",
                false,
                List.of(
                        "state: dirty_worktree",
                        "reason: Repository working tree is not clean",
                        "action: Clean or discard local changes manually before retrying close",
                        "retryable: false")));

        mockMvc.perform(post("/api/sessions/12/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession '12' cannot finish closing: Repository working tree is not clean"))
                .andExpect(jsonPath("$.details[0]").value("state: dirty_worktree"))
                .andExpect(jsonPath("$.state").value("dirty_worktree"))
                .andExpect(jsonPath("$.reason").value("Repository working tree is not clean"))
                .andExpect(jsonPath("$.action").value("Clean or discard local changes manually before retrying close"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void closeSessionConversationViewReturnsConflictWhenReconciliationBlocksClosing() throws Exception {
        when(workSessionService.closeSessionConversationView(12L)).thenThrow(new WorkSessionCloseBlockedException(
                "WorkSession '12' cannot finish closing: Repository working tree is not clean",
                "dirty_worktree",
                "Repository working tree is not clean",
                "Clean or discard local changes manually before retrying close",
                false,
                List.of(
                        "state: dirty_worktree",
                        "reason: Repository working tree is not clean",
                        "action: Clean or discard local changes manually before retrying close",
                        "retryable: false")));

        mockMvc.perform(post("/api/sessions/12/close/conversation-view"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.state").value("dirty_worktree"))
                .andExpect(jsonPath("$.reason").value("Repository working tree is not clean"))
                .andExpect(jsonPath("$.action").value("Clean or discard local changes manually before retrying close"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void publishSessionReturnsPublishedSession() throws Exception {
        when(workSessionGitHubService.publishSession(12L, new PublishWorkSessionRequest("Ship current work")))
                .thenReturn(new WorkSessionResponse(
                        12L,
                        7L,
                        WorkSessionStatus.OPEN,
                        WorkSessionOperationalState.IDLE,
                        "Inspect project state",
                        "main",
                        "atenea/session-12",
                        "thread-1",
                        "https://github.com/acme/atenea/pull/42",
                        WorkSessionPullRequestStatus.OPEN,
                        "abc123",
                        Instant.parse("2026-03-25T10:05:00Z"),
                        Instant.parse("2026-03-25T10:06:00Z"),
                        Instant.parse("2026-03-25T10:07:00Z"),
                        null,
                        null,
                        null,
                        null,
                        false,
                        new SessionOperationalSnapshotResponse(true, true, "atenea/session-12", false)));

        mockMvc.perform(post("/api/sessions/12/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commitMessage": "Ship current work"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequestUrl").value("https://github.com/acme/atenea/pull/42"))
                .andExpect(jsonPath("$.pullRequestStatus").value("OPEN"))
                .andExpect(jsonPath("$.finalCommitSha").value("abc123"));
    }

    @Test
    void publishSessionConversationViewReturnsPublishedConversationState() throws Exception {
        when(workSessionGitHubService.publishSessionConversationView(12L, new PublishWorkSessionRequest("Ship current work")))
                .thenReturn(new PublishWorkSessionConversationViewResponse(
                        new WorkSessionConversationViewResponse(
                                new WorkSessionViewResponse(
                                        new WorkSessionResponse(
                                                12L,
                                                7L,
                                                WorkSessionStatus.OPEN,
                                                WorkSessionOperationalState.IDLE,
                                                "Inspect project state",
                                                "main",
                                                "atenea/session-12",
                                                "thread-1",
                                                "https://github.com/acme/atenea/pull/42",
                                                WorkSessionPullRequestStatus.OPEN,
                                                "abc123",
                                                Instant.parse("2026-03-25T10:05:00Z"),
                                                Instant.parse("2026-03-25T10:06:00Z"),
                                                Instant.parse("2026-03-25T10:07:00Z"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                false,
                                                new SessionOperationalSnapshotResponse(
                                                        true,
                                                        true,
                                                        "atenea/session-12",
                                                        false)),
                                        false,
                                        true,
                                        null,
                                        null,
                                        "Current status summary"),
                                List.of(),
                                20,
                                false)));

        mockMvc.perform(post("/api/sessions/12/publish/conversation-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "commitMessage": "Ship current work"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.pullRequestStatus").value("OPEN"))
                .andExpect(jsonPath("$.view.view.session.finalCommitSha").value("abc123"));
    }

    @Test
    void publishSessionReturnsConflictWhenPublishIsBlocked() throws Exception {
        when(workSessionGitHubService.publishSession(12L, new PublishWorkSessionRequest(null)))
                .thenThrow(new WorkSessionPublishConflictException(
                        12L,
                        "publish requires reviewable changes in the workspace branch"));

        mockMvc.perform(post("/api/sessions/12/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "WorkSession '12' cannot be published: publish requires reviewable changes in the workspace branch"));
    }

    @Test
    void syncPullRequestReturnsUpdatedSession() throws Exception {
        when(workSessionGitHubService.syncPullRequest(12L)).thenReturn(new WorkSessionResponse(
                12L,
                7L,
                WorkSessionStatus.OPEN,
                WorkSessionOperationalState.IDLE,
                "Inspect project state",
                "main",
                "atenea/session-12",
                "thread-1",
                "https://github.com/acme/atenea/pull/42",
                WorkSessionPullRequestStatus.MERGED,
                "abc123",
                Instant.parse("2026-03-25T10:05:00Z"),
                Instant.parse("2026-03-25T10:06:00Z"),
                Instant.parse("2026-03-25T10:07:00Z"),
                null,
                null,
                null,
                null,
                false,
                new SessionOperationalSnapshotResponse(true, true, "atenea/session-12", false)));

        mockMvc.perform(post("/api/sessions/12/pull-request/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pullRequestStatus").value("MERGED"));
    }

    @Test
    void syncPullRequestConversationViewReturnsUpdatedConversationState() throws Exception {
        when(workSessionGitHubService.syncPullRequestConversationView(12L))
                .thenReturn(new SyncWorkSessionPullRequestConversationViewResponse(
                        new WorkSessionConversationViewResponse(
                                new WorkSessionViewResponse(
                                        new WorkSessionResponse(
                                                12L,
                                                7L,
                                                WorkSessionStatus.OPEN,
                                                WorkSessionOperationalState.IDLE,
                                                "Inspect project state",
                                                "main",
                                                "atenea/session-12",
                                                "thread-1",
                                                "https://github.com/acme/atenea/pull/42",
                                                WorkSessionPullRequestStatus.MERGED,
                                                "abc123",
                                                Instant.parse("2026-03-25T10:05:00Z"),
                                                Instant.parse("2026-03-25T10:06:00Z"),
                                                Instant.parse("2026-03-25T10:07:00Z"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                false,
                                                new SessionOperationalSnapshotResponse(
                                                        true,
                                                        true,
                                                        "atenea/session-12",
                                                        false)),
                                        false,
                                        true,
                                        null,
                                        null,
                                        "Current status summary"),
                                List.of(),
                                20,
                                false)));

        mockMvc.perform(post("/api/sessions/12/pull-request/sync/conversation-view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.view.session.pullRequestStatus").value("MERGED"));
    }

    private static WorkSessionResponse sessionResponse(
            WorkSessionStatus status,
            WorkSessionOperationalState operationalState,
            String externalThreadId,
            Instant publishedAt,
            Instant closedAt
    ) {
        return sessionResponse(
                status,
                operationalState,
                externalThreadId,
                publishedAt,
                closedAt,
                null,
                null,
                null,
                false,
                new SessionOperationalSnapshotResponse(true, true, "main", false));
    }

    private static WorkSessionResponse sessionResponse(
            WorkSessionStatus status,
            WorkSessionOperationalState operationalState,
            String externalThreadId,
            Instant publishedAt,
            Instant closedAt,
            SessionOperationalSnapshotResponse snapshot
    ) {
        return sessionResponse(
                status,
                operationalState,
                externalThreadId,
                publishedAt,
                closedAt,
                null,
                null,
                null,
                false,
                snapshot);
    }

    private static WorkSessionResponse sessionResponse(
            WorkSessionStatus status,
            WorkSessionOperationalState operationalState,
            String externalThreadId,
            Instant publishedAt,
            Instant closedAt,
            String closeBlockedState,
            String closeBlockedReason,
            String closeBlockedAction,
            boolean closeRetryable,
            SessionOperationalSnapshotResponse snapshot
    ) {
        return new WorkSessionResponse(
                12L,
                7L,
                status,
                operationalState,
                "Inspect project state",
                "main",
                "atenea/session-12",
                externalThreadId,
                null,
                WorkSessionPullRequestStatus.NOT_CREATED,
                null,
                Instant.parse("2026-03-25T10:05:00Z"),
                Instant.parse("2026-03-25T10:06:00Z"),
                publishedAt,
                closedAt,
                closeBlockedState,
                closeBlockedReason,
                closeBlockedAction,
                closeRetryable,
                snapshot);
    }
}
