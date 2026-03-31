package com.atenea.service.core;

import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.worksession.CreateSessionTurnConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.PublishWorkSessionConversationViewResponse;
import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SyncWorkSessionPullRequestConversationViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.service.mobile.MobileSessionService;
import com.atenea.service.project.ProjectOverviewService;
import com.atenea.service.worksession.SessionDeliverableGenerationService;
import com.atenea.service.worksession.SessionDeliverableService;
import com.atenea.service.worksession.SessionTurnService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.atenea.service.worksession.WorkSessionService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DevelopmentCoreDomainHandler implements CoreDomainHandler {

    private final WorkSessionService workSessionService;
    private final SessionTurnService sessionTurnService;
    private final WorkSessionGitHubService workSessionGitHubService;
    private final SessionDeliverableService sessionDeliverableService;
    private final SessionDeliverableGenerationService sessionDeliverableGenerationService;
    private final ProjectOverviewService projectOverviewService;
    private final MobileSessionService mobileSessionService;
    private final CoreOperatorContextService coreOperatorContextService;

    public DevelopmentCoreDomainHandler(
            WorkSessionService workSessionService,
            SessionTurnService sessionTurnService,
            WorkSessionGitHubService workSessionGitHubService,
            SessionDeliverableService sessionDeliverableService,
            SessionDeliverableGenerationService sessionDeliverableGenerationService,
            ProjectOverviewService projectOverviewService,
            MobileSessionService mobileSessionService,
            CoreOperatorContextService coreOperatorContextService
    ) {
        this.workSessionService = workSessionService;
        this.sessionTurnService = sessionTurnService;
        this.workSessionGitHubService = workSessionGitHubService;
        this.sessionDeliverableService = sessionDeliverableService;
        this.sessionDeliverableGenerationService = sessionDeliverableGenerationService;
        this.projectOverviewService = projectOverviewService;
        this.mobileSessionService = mobileSessionService;
        this.coreOperatorContextService = coreOperatorContextService;
    }

    @Override
    public CoreDomain domain() {
        return CoreDomain.DEVELOPMENT;
    }

    @Override
    public CoreCommandExecutionResult execute(CoreIntentEnvelope intent, CoreExecutionContext context) {
        return switch (intent.capability()) {
            case "list_projects_overview" -> listProjectsOverview();
            case "get_project_overview" -> getProjectOverview(intent.parameters());
            case "activate_project_context" -> activateProjectContext(intent.parameters(), context);
            case "create_work_session" -> createWorkSession(intent.parameters());
            case "continue_work_session" -> continueWorkSession(intent.parameters());
            case "publish_work_session" -> publishWorkSession(intent.parameters());
            case "sync_work_session_pull_request" -> syncWorkSessionPullRequest(intent.parameters());
            case "get_session_summary" -> getSessionSummary(intent.parameters());
            case "get_session_deliverables" -> getSessionDeliverables(intent.parameters());
            case "generate_session_deliverable" -> generateSessionDeliverable(intent.parameters());
            case "close_work_session" -> closeWorkSession(intent.parameters());
            default -> throw new CoreCapabilityDisabledException(
                    "Development capability is not supported by the current Core handler: " + intent.capability());
        };
    }

    private CoreCommandExecutionResult listProjectsOverview() {
        List<ProjectOverviewResponse> overview = projectOverviewService.getOverview();
        return new CoreCommandExecutionResult(
                CoreResultType.PROJECT_OVERVIEW_LIST,
                null,
                null,
                overview,
                "Returned project overview for " + overview.size() + " projects",
                "I gathered the current status of the registered projects.",
                "I gathered the current status of the registered projects.");
    }

    private CoreCommandExecutionResult getProjectOverview(Map<String, Object> parameters) {
        Long projectId = requireLong(parameters, "projectId");
        ProjectOverviewResponse overview = projectOverviewService.getOverview().stream()
                .filter(candidate -> candidate.project().id().equals(projectId))
                .findFirst()
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: projectId"));
        return new CoreCommandExecutionResult(
                CoreResultType.PROJECT_OVERVIEW,
                CoreTargetType.PROJECT,
                projectId,
                overview,
                "Returned project overview for project " + projectId,
                "I gathered the current status of the selected project.",
                "I gathered the current status of the selected project.");
    }

    private CoreCommandExecutionResult activateProjectContext(Map<String, Object> parameters, CoreExecutionContext context) {
        Long projectId = requireLong(parameters, "projectId");
        coreOperatorContextService.activateProject(context.operatorKey(), projectId, context.commandId());
        ProjectOverviewResponse overview = projectOverviewService.getOverview().stream()
                .filter(candidate -> candidate.project().id().equals(projectId))
                .findFirst()
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: projectId"));
        return new CoreCommandExecutionResult(
                CoreResultType.PROJECT_CONTEXT,
                CoreTargetType.PROJECT,
                projectId,
                Map.of(
                        "activeProjectId", projectId,
                        "project", overview),
                "Activated project context for project " + projectId,
                "The selected project is now the active project context.",
                "The selected project is now the active project context.");
    }

    private CoreCommandExecutionResult createWorkSession(Map<String, Object> parameters) {
        Long projectId = requireLong(parameters, "projectId");
        String title = requireText(parameters, "title");
        ResolveWorkSessionConversationViewResponse response = workSessionService.resolveSessionConversationView(
                projectId,
                new ResolveWorkSessionRequest(title, null));
        Long sessionId = response.view().view().session().id();
        String operatorMessage = response.created()
                ? "A WorkSession was created and the conversation view is ready."
                : "The existing WorkSession was resolved and the conversation view is ready.";
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Resolved WorkSession conversation view for project " + projectId,
                operatorMessage,
                operatorMessage);
    }

    private CoreCommandExecutionResult continueWorkSession(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        String message = requireText(parameters, "message");
        sessionTurnService.createTurn(sessionId, new CreateSessionTurnRequest(message));
        WorkSessionConversationViewResponse view = workSessionService.getSessionConversationView(sessionId);
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                new CreateSessionTurnConversationViewResponse(view),
                "Created WorkSession turn and returned conversation view for session " + sessionId,
                "The active WorkSession was continued successfully.",
                "The active work session was continued successfully.");
    }

    private CoreCommandExecutionResult publishWorkSession(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        PublishWorkSessionConversationViewResponse response = workSessionGitHubService.publishSessionConversationView(
                sessionId,
                new PublishWorkSessionRequest(null));
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Published WorkSession " + sessionId + " and returned the updated conversation view",
                "The active WorkSession was published successfully.",
                "The active work session was published successfully.");
    }

    private CoreCommandExecutionResult syncWorkSessionPullRequest(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        SyncWorkSessionPullRequestConversationViewResponse response =
                workSessionGitHubService.syncPullRequestConversationView(sessionId);
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Synchronized pull request state for WorkSession " + sessionId,
                "The WorkSession pull request state was synchronized successfully.",
                "The work session pull request state was synchronized successfully.");
    }

    private CoreCommandExecutionResult getSessionSummary(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        MobileSessionSummaryResponse response = mobileSessionService.getSessionSummary(sessionId);
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_SUMMARY,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Returned session summary for WorkSession " + sessionId,
                "I gathered the current session summary.",
                "I gathered the current session summary.");
    }

    private CoreCommandExecutionResult getSessionDeliverables(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        SessionDeliverablesViewResponse response = sessionDeliverableService.getDeliverablesView(sessionId);
        return new CoreCommandExecutionResult(
                CoreResultType.SESSION_DELIVERABLES_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Returned deliverables view for WorkSession " + sessionId,
                "I gathered the deliverables for the current session.",
                "I gathered the deliverables for the current session.");
    }

    private CoreCommandExecutionResult generateSessionDeliverable(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        String deliverableType = requireText(parameters, "deliverableType");
        SessionDeliverableResponse response = sessionDeliverableGenerationService.generateDeliverable(
                sessionId,
                SessionDeliverableType.valueOf(deliverableType));
        return new CoreCommandExecutionResult(
                CoreResultType.SESSION_DELIVERABLE,
                CoreTargetType.SESSION_DELIVERABLE,
                response.id(),
                response,
                "Generated deliverable " + response.type() + " for WorkSession " + sessionId,
                "The requested deliverable was generated successfully.",
                "The requested deliverable was generated successfully.");
    }

    private CoreCommandExecutionResult closeWorkSession(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        var response = workSessionService.closeSessionConversationView(sessionId);
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Closed WorkSession " + sessionId + " and returned the updated conversation view",
                "The WorkSession was closed successfully.",
                "The work session was closed successfully.");
    }

    private Long requireLong(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new CoreInvalidContextException("Missing or invalid Core parameter: " + key);
    }

    private String requireText(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw new CoreInvalidContextException("Missing or invalid Core parameter: " + key);
    }
}
