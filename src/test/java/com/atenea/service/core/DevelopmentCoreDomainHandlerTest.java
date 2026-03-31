package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.project.ProjectResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SessionOperationalSnapshotResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionOperationalState;
import com.atenea.api.worksession.WorkSessionResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.mobile.MobileSessionService;
import com.atenea.service.project.ProjectOverviewService;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DevelopmentCoreDomainHandlerTest {

    @Mock
    private WorkSessionService workSessionService;

    @Mock
    private SessionTurnService sessionTurnService;

    @Mock
    private WorkSessionGitHubService workSessionGitHubService;

    @Mock
    private SessionDeliverableService sessionDeliverableService;

    @Mock
    private SessionDeliverableGenerationService sessionDeliverableGenerationService;

    @Mock
    private ProjectOverviewService projectOverviewService;

    @Mock
    private MobileSessionService mobileSessionService;

    @Mock
    private CoreOperatorContextService coreOperatorContextService;

    private DevelopmentCoreDomainHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DevelopmentCoreDomainHandler(
                workSessionService,
                sessionTurnService,
                workSessionGitHubService,
                sessionDeliverableService,
                sessionDeliverableGenerationService,
                projectOverviewService,
                mobileSessionService,
                coreOperatorContextService);
    }

    @Test
    void createWorkSessionDelegatesToResolveConversationView() {
        WorkSessionConversationViewResponse conversationView = conversationView(44L);
        when(workSessionService.resolveSessionConversationView(7L, new ResolveWorkSessionRequest("Nueva sesion", null)))
                .thenReturn(new ResolveWorkSessionConversationViewResponse(true, conversationView));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "CREATE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "create_work_session",
                        Map.of("projectId", 7L, "title", "Nueva sesion"),
                        BigDecimal.valueOf(0.90),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, null, "default", false, null));

        assertEquals(CoreResultType.WORK_SESSION_CONVERSATION_VIEW, result.resultType());
        assertEquals(CoreTargetType.WORK_SESSION, result.targetType());
        assertEquals(44L, result.targetId());
    }

    @Test
    void continueWorkSessionDelegatesToTurnCreationAndConversationRead() {
        WorkSessionConversationViewResponse conversationView = conversationView(12L);
        when(workSessionService.getSessionConversationView(12L)).thenReturn(conversationView);

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "CONTINUE_WORK_SESSION",
                        CoreDomain.DEVELOPMENT,
                        "continue_work_session",
                        Map.of("workSessionId", 12L, "message", "Sigue con esto"),
                        BigDecimal.valueOf(0.99),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, 12L, "default", false, null));

        verify(sessionTurnService).createTurn(12L, new CreateSessionTurnRequest("Sigue con esto"));
        verify(workSessionService).getSessionConversationView(12L);
        assertEquals(CoreResultType.WORK_SESSION_CONVERSATION_VIEW, result.resultType());
        assertEquals(12L, result.targetId());
    }

    @Test
    void getProjectOverviewDelegatesToProjectOverviewService() {
        when(projectOverviewService.getOverview()).thenReturn(List.of(projectOverview(7L)));

        CoreCommandExecutionResult result = handler.execute(
                new CoreIntentEnvelope(
                        "GET_PROJECT_OVERVIEW",
                        CoreDomain.DEVELOPMENT,
                        "get_project_overview",
                        Map.of("projectId", 7L),
                        BigDecimal.valueOf(0.95),
                        CoreRiskLevel.READ,
                        false),
                new CoreExecutionContext(101L, 7L, null, "default", false, null));

        assertEquals(CoreResultType.PROJECT_OVERVIEW, result.resultType());
        assertEquals(CoreTargetType.PROJECT, result.targetType());
        assertEquals(7L, result.targetId());
    }

    private static WorkSessionConversationViewResponse conversationView(Long sessionId) {
        return new WorkSessionConversationViewResponse(
                new WorkSessionViewResponse(
                        new WorkSessionResponse(
                                sessionId,
                                7L,
                                WorkSessionStatus.OPEN,
                                WorkSessionOperationalState.IDLE,
                                "Trabajo",
                                "main",
                                "atenea/session-" + sessionId,
                                "thread-1",
                                null,
                                WorkSessionPullRequestStatus.NOT_CREATED,
                                null,
                                Instant.parse("2026-03-30T10:00:00Z"),
                                Instant.parse("2026-03-30T10:00:00Z"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                new SessionOperationalSnapshotResponse(true, true, "main", false)),
                        false,
                        true,
                        null,
                        null,
                        null),
                List.of(),
                20,
                false);
    }

    private static ProjectOverviewResponse projectOverview(Long projectId) {
        return new ProjectOverviewResponse(
                new ProjectResponse(
                        projectId,
                        "Atenea",
                        "Core backend",
                        "/repos/atenea",
                        "main",
                        Instant.parse("2026-03-30T10:00:00Z"),
                        Instant.parse("2026-03-30T10:00:00Z")),
                new ProjectOverviewResponse.WorkSessionOverviewResponse(
                        44L,
                        true,
                        WorkSessionStatus.OPEN,
                        "Trabajo",
                        "main",
                        "thread-1",
                        null,
                        WorkSessionPullRequestStatus.NOT_CREATED,
                        null,
                        null,
                        null,
                        false,
                        true,
                        true,
                        "main",
                        false,
                        null,
                        Instant.parse("2026-03-30T10:00:00Z"),
                        Instant.parse("2026-03-30T10:00:00Z"),
                        null));
    }
}
