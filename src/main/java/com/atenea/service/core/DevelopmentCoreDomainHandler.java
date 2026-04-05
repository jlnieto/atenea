package com.atenea.service.core;

import com.atenea.api.mobile.MobileSessionSummaryResponse;
import com.atenea.api.mobile.MobileSessionBlockerResponse;
import com.atenea.api.project.ProjectOverviewResponse;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.CreateSessionTurnConversationViewResponse;
import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.MarkPriceEstimateBilledRequest;
import com.atenea.api.worksession.PublishWorkSessionConversationViewResponse;
import com.atenea.api.worksession.PublishWorkSessionRequest;
import com.atenea.api.worksession.ResolveWorkSessionConversationViewResponse;
import com.atenea.api.worksession.ResolveWorkSessionRequest;
import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.api.worksession.SyncWorkSessionPullRequestConversationViewResponse;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.api.worksession.WorkSessionViewResponse;
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
import java.util.ArrayList;
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
            case "approve_session_deliverable" -> approveSessionDeliverable(intent.parameters());
            case "mark_price_estimate_billed" -> markPriceEstimateBilled(intent.parameters());
            case "close_work_session" -> closeWorkSession(intent.parameters());
            default -> throw new CoreCapabilityDisabledException(
                    "Development capability is not supported by the current Core handler: " + intent.capability());
        };
    }

    private CoreCommandExecutionResult listProjectsOverview() {
        List<ProjectOverviewResponse> overview = projectOverviewService.getOverview();
        String spokenSummary = buildPortfolioSpokenSummary(overview);
        return new CoreCommandExecutionResult(
                CoreResultType.PROJECT_OVERVIEW_LIST,
                null,
                null,
                overview,
                "Returned project overview for " + overview.size() + " projects",
                spokenSummary,
                spokenSummary);
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
                buildProjectSpokenSummary(overview),
                buildProjectSpokenSummary(overview));
    }

    private String buildPortfolioSpokenSummary(List<ProjectOverviewResponse> overview) {
        if (overview.isEmpty()) {
            return "No hay proyectos registrados en este entorno.";
        }

        List<String> projectSummaries = new ArrayList<>();
        for (ProjectOverviewResponse projectOverview : overview.stream().limit(3).toList()) {
            projectSummaries.add(buildProjectClause(projectOverview));
        }

        StringBuilder summary = new StringBuilder("Hay ")
                .append(overview.size())
                .append(overview.size() == 1 ? " proyecto registrado. " : " proyectos registrados. ")
                .append(String.join(" ", projectSummaries));

        if (overview.size() > 3) {
            summary.append(" Hay ")
                    .append(overview.size() - 3)
                    .append(" proyectos más en la lista.");
        }
        return summary.toString().trim();
    }

    private String buildProjectSpokenSummary(ProjectOverviewResponse overview) {
        return buildProjectClause(overview);
    }

    private String buildProjectClause(ProjectOverviewResponse overview) {
        StringBuilder clause = new StringBuilder()
                .append(spokenProjectLabel(overview))
                .append(": ");

        ProjectOverviewResponse.WorkSessionOverviewResponse workSession = overview.workSession();
        if (workSession == null) {
            clause.append("sin sesión activa.");
            return clause.toString();
        }

        clause.append("sesión ").append(spanishSessionStatus(workSession.status()));

        if (workSession.runInProgress()) {
            clause.append(", con ejecución en curso");
        }
        clause.append(".");

        String spokenTitle = spokenSessionTitle(workSession.title());
        if (spokenTitle != null) {
            clause.append(" Título ").append(spokenTitle).append(".");
        }

        clause.append(" ").append(spanishPullRequestStatus(workSession.pullRequestStatus())).append(".");

        String spokenBranch = spokenBranch(workSession.currentBranch(), workSession.baseBranch());
        if (spokenBranch != null) {
            clause.append(" Rama ").append(spokenBranch).append(".");
        }

        return clause.toString();
    }

    private String spanishSessionStatus(com.atenea.persistence.worksession.WorkSessionStatus status) {
        if (status == null) {
            return "sin estado";
        }
        return switch (status) {
            case OPEN -> "abierta";
            case CLOSING -> "en cierre";
            case CLOSED -> "cerrada";
        };
    }

    private String spanishPullRequestStatus(com.atenea.persistence.worksession.WorkSessionPullRequestStatus status) {
        if (status == null) {
            return "Estado de pull request no disponible";
        }
        return switch (status) {
            case NOT_CREATED -> "Sin pull request";
            case OPEN -> "Pull request abierta";
            case MERGED -> "Pull request fusionada";
            case DECLINED -> "Pull request rechazada";
        };
    }

    private String spokenLabel(String value) {
        if (value == null || value.isBlank()) {
            return "Proyecto";
        }
        return value.replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String spokenProjectLabel(ProjectOverviewResponse overview) {
        String normalized = spokenLabel(overview.project().name());
        if (normalized.matches(".*[0-9a-f]{8} [0-9a-f]{4} [0-9a-f]{4} [0-9a-f]{4} [0-9a-f]{12}.*")) {
            return "proyecto " + overview.project().id();
        }
        return normalized;
    }

    private String spokenSessionTitle(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[.]{2,}", ".")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized.replaceFirst("[.]+$", "").trim();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private String spokenBranch(String currentBranch, String baseBranch) {
        if (currentBranch == null || currentBranch.isBlank()) {
            return null;
        }
        String normalized = currentBranch.trim();
        if (normalized.startsWith("atenea/session-")) {
            return null;
        }
        if (baseBranch != null && normalized.equalsIgnoreCase(baseBranch.trim())) {
            return null;
        }
        if (normalized.matches(".*[0-9a-f]{8}-[0-9a-f-]{27,}.*")) {
            return null;
        }
        return normalized.replace('_', ' ')
                .replace('-', ' ')
                .replace('/', ' ')
                .replaceAll("\\s+", " ")
                .trim();
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
                "El proyecto seleccionado ya es el contexto activo.",
                "El proyecto seleccionado ya es el contexto activo.");
    }

    private CoreCommandExecutionResult createWorkSession(Map<String, Object> parameters) {
        Long projectId = requireLong(parameters, "projectId");
        String title = requireText(parameters, "title");
        ResolveWorkSessionConversationViewResponse response = workSessionService.resolveSessionConversationView(
                projectId,
                new ResolveWorkSessionRequest(title, null));
        Long sessionId = response.view().view().session().id();
        String operatorMessage = response.created()
                ? "He creado una WorkSession y la conversación ya está lista."
                : "He reutilizado la WorkSession existente y la conversación ya está lista.";
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Resolved WorkSession conversation view for project " + projectId,
                operatorMessage,
                response.created()
                        ? "He creado una sesión de trabajo y la conversación ya está lista."
                        : "He reutilizado la sesión de trabajo existente y la conversación ya está lista.");
    }

    private CoreCommandExecutionResult continueWorkSession(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        String message = requireText(parameters, "message");
        sessionTurnService.createTurn(sessionId, new CreateSessionTurnRequest(message));
        WorkSessionConversationViewResponse view = workSessionService.getSessionConversationView(sessionId);
        String continuationSummary = buildContinueWorkSummary(view);
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_CONVERSATION_VIEW,
                CoreTargetType.WORK_SESSION,
                sessionId,
                new CreateSessionTurnConversationViewResponse(view),
                "Created WorkSession turn and returned conversation view for session " + sessionId,
                continuationSummary,
                continuationSummary);
    }

    private String buildContinueWorkSummary(WorkSessionConversationViewResponse response) {
        if (response == null || response.view() == null) {
            return "He enviado la instrucción a la sesión activa.";
        }

        String lastAgentResponse = spokenExcerpt(response.view().lastAgentResponse());
        if (lastAgentResponse != null) {
            return "Codex responde: " + lastAgentResponse;
        }

        if (response.view().runInProgress()) {
            return "He enviado la instrucción a la sesión activa. Codex sigue trabajando en ello.";
        }

        return "He enviado la instrucción a la sesión activa.";
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
                "He publicado la WorkSession activa correctamente.",
                "He publicado la sesión de trabajo activa correctamente.");
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
                "He sincronizado correctamente el estado de la pull request de la sesión.",
                "He sincronizado correctamente el estado de la pul request de la sesión.");
    }

    private CoreCommandExecutionResult getSessionSummary(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        MobileSessionSummaryResponse response = mobileSessionService.getSessionSummary(sessionId);
        String spokenSummary = buildSessionSpokenSummary(response);
        return new CoreCommandExecutionResult(
                CoreResultType.WORK_SESSION_SUMMARY,
                CoreTargetType.WORK_SESSION,
                sessionId,
                response,
                "Returned session summary for WorkSession " + sessionId,
                spokenSummary,
                spokenSummary);
    }

    private String buildSessionSpokenSummary(MobileSessionSummaryResponse response) {
        WorkSessionConversationViewResponse conversation = response.conversation();
        WorkSessionViewResponse view = conversation.view();
        var session = view.session();
        String spokenTitle = spokenSessionTitle(session.title());
        String latestProgress = response.insights() == null || response.insights().latestProgress() == null
                ? "No hay un avance resumido disponible"
                : response.insights().latestProgress();
        String currentBlocker = blockerSummary(response.insights() == null ? null : response.insights().currentBlocker());
        String nextStep = response.insights() == null || response.insights().nextStepRecommended() == null
                ? "Revisar manualmente la sesión actual"
                : response.insights().nextStepRecommended();

        StringBuilder summary = new StringBuilder();
        if (spokenTitle != null) {
            summary.append("La sesión ").append(spokenTitle).append(" está ")
                    .append(spanishSessionStatus(session.status())).append(".");
        } else {
            summary.append("La sesión está ").append(spanishSessionStatus(session.status())).append(".");
        }

        if (view.runInProgress()) {
            summary.append(" Hay una ejecución en curso.");
        } else {
            summary.append(" No hay una ejecución en curso.");
        }

        summary.append(" Último avance: ").append(latestProgress).append(".");
        summary.append(" Bloqueo actual: ").append(currentBlocker).append(".");
        summary.append(" Siguiente paso recomendado: ").append(nextStep).append(".");

        summary.append(" ").append(spanishPullRequestStatus(session.pullRequestStatus())).append(".");
        appendDeliverableStatus(summary, response.approvedDeliverables(), response.approvedPriceEstimate());
        return summary.toString().trim();
    }

    private String blockerSummary(MobileSessionBlockerResponse blocker) {
        if (blocker == null || blocker.summary() == null || blocker.summary().isBlank()) {
            return "Sin bloqueo activo";
        }
        if ("NONE".equalsIgnoreCase(blocker.category())) {
            return blocker.summary();
        }
        if ("BUSINESS".equalsIgnoreCase(blocker.category())) {
            return "De negocio. " + blocker.summary();
        }
        return "Técnico. " + blocker.summary();
    }

    private String spokenExcerpt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replace("```", " ")
                .replace('\n', '.')
                .replace('\r', '.')
                .replace('`', ' ')
                .replaceAll("#{1,6}\\s*", " ")
                .replaceAll("\\s*\\.\\s*", ". ")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized
                .replaceFirst("(?i)^punto actual\\.?\\s*", "")
                .replaceFirst("(?i)^estado actual\\.?\\s*", "")
                .replaceFirst("[.]+$", "")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        int sentenceBoundary = normalized.indexOf(". ");
        if (sentenceBoundary > 0) {
            normalized = normalized.substring(0, sentenceBoundary + 1).trim();
        }
        normalized = normalized.replaceFirst("[.]+$", "").trim();
        if (normalized.length() > 220) {
            return normalized.substring(0, 217).trim() + "...";
        }
        return normalized;
    }

    private void appendDeliverableStatus(
            StringBuilder summary,
            SessionDeliverablesViewResponse approvedDeliverables,
            ApprovedPriceEstimateSummaryResponse approvedPriceEstimate
    ) {
        int approvedCount = approvedDeliverables == null || approvedDeliverables.deliverables() == null
                ? 0
                : approvedDeliverables.deliverables().size();
        if (approvedCount > 0) {
            summary.append(" Hay ").append(approvedCount)
                    .append(approvedCount == 1 ? " entregable aprobado." : " entregables aprobados.");
        }

        if (approvedPriceEstimate == null || approvedPriceEstimate.billingStatus() == null) {
            return;
        }

        switch (approvedPriceEstimate.billingStatus()) {
            case READY -> summary.append(" El presupuesto aprobado está listo para facturar.");
            case BILLED -> summary.append(" El presupuesto aprobado ya está facturado.");
        }
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
                "He reunido los entregables de la sesión actual.",
                "He reunido los entregables de la sesión actual.");
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
                "He generado correctamente el entregable solicitado.",
                "He generado correctamente el entregable solicitado.");
    }

    private CoreCommandExecutionResult approveSessionDeliverable(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        Long deliverableId = requireLong(parameters, "deliverableId");
        SessionDeliverableResponse response = sessionDeliverableService.approveDeliverable(sessionId, deliverableId);
        return new CoreCommandExecutionResult(
                CoreResultType.SESSION_DELIVERABLE,
                CoreTargetType.SESSION_DELIVERABLE,
                response.id(),
                response,
                "Approved deliverable " + deliverableId + " for WorkSession " + sessionId,
                "He aprobado correctamente el entregable solicitado.",
                "He aprobado correctamente el entregable solicitado.");
    }

    private CoreCommandExecutionResult markPriceEstimateBilled(Map<String, Object> parameters) {
        Long sessionId = requireLong(parameters, "workSessionId");
        Long deliverableId = requireLong(parameters, "deliverableId");
        String billingReference = requireText(parameters, "billingReference");
        SessionDeliverableResponse response = sessionDeliverableService.markPriceEstimateBilled(
                sessionId,
                deliverableId,
                new MarkPriceEstimateBilledRequest(billingReference));
        return new CoreCommandExecutionResult(
                CoreResultType.SESSION_DELIVERABLE,
                CoreTargetType.SESSION_DELIVERABLE,
                response.id(),
                response,
                "Marked deliverable " + deliverableId + " as billed for WorkSession " + sessionId,
                "He marcado correctamente el presupuesto aprobado como facturado.",
                "He marcado correctamente el presupuesto aprobado como facturado.");
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
                "He cerrado la WorkSession correctamente.",
                "He cerrado la sesión de trabajo correctamente.");
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
