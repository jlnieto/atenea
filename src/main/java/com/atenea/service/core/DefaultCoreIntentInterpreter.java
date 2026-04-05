package com.atenea.service.core;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.project.ProjectEntity;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultCoreIntentInterpreter implements CoreIntentInterpreter {

    private final CoreProjectResolver coreProjectResolver;
    private final CoreWorkSessionResolver coreWorkSessionResolver;

    public DefaultCoreIntentInterpreter(
            CoreProjectResolver coreProjectResolver,
            CoreWorkSessionResolver coreWorkSessionResolver
    ) {
        this.coreProjectResolver = coreProjectResolver;
        this.coreWorkSessionResolver = coreWorkSessionResolver;
    }

    @Override
    public CoreInterpretationResult interpret(CreateCoreCommandRequest request) {
        CoreRequestContext context = request.context();
        String input = request.input().trim();
        String normalized = normalize(input);

        if (looksLikePortfolioStatus(normalized)) {
            return proposal(
                    "LIST_PROJECTS_OVERVIEW",
                    "list_projects_overview",
                    Map.of(),
                    BigDecimal.valueOf(0.96),
                    "portfolio_status_query");
        }

        if (looksLikeOpenSession(normalized)) {
            ProjectEntity project = resolveProject(context, input);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("projectId", project.getId());
            parameters.put("title", extractSessionTitle(input, project.getName()));
            return proposal(
                    "CREATE_WORK_SESSION",
                    "create_work_session",
                    parameters,
                    BigDecimal.valueOf(0.93),
                    context != null && context.projectId() != null
                            ? "explicit_project_context"
                            : "resolved_project_context");
        }

        if (looksLikeGenerateDeliverable(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            SessionDeliverableType type = resolveDeliverableType(normalized);
            return proposal(
                    "GENERATE_SESSION_DELIVERABLE",
                    "generate_session_deliverable",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId(),
                            "deliverableType", type.name()),
                    BigDecimal.valueOf(0.92),
                    "deliverable_generation_request");
        }

        if (looksLikeApproveDeliverable(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "APPROVE_SESSION_DELIVERABLE",
                    "approve_session_deliverable",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId(),
                            "deliverableId", extractDeliverableId(input)),
                    BigDecimal.valueOf(0.95),
                    "deliverable_approval_request");
        }

        if (looksLikeMarkPriceEstimateBilled(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "MARK_PRICE_ESTIMATE_BILLED",
                    "mark_price_estimate_billed",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId(),
                            "deliverableId", extractDeliverableId(input),
                            "billingReference", extractBillingReference(input)),
                    BigDecimal.valueOf(0.96),
                    "price_estimate_billing_request");
        }

        if (looksLikeDeliverablesRead(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "GET_SESSION_DELIVERABLES",
                    "get_session_deliverables",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId()),
                    BigDecimal.valueOf(0.94),
                    "session_deliverables_query");
        }

        if (looksLikePublish(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "PUBLISH_WORK_SESSION",
                    "publish_work_session",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId()),
                    BigDecimal.valueOf(0.95),
                    "publish_request");
        }

        if (looksLikeSyncPullRequest(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "SYNC_WORK_SESSION_PULL_REQUEST",
                    "sync_work_session_pull_request",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId()),
                    BigDecimal.valueOf(0.95),
                    "sync_pull_request_request");
        }

        if (looksLikeClose(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "CLOSE_WORK_SESSION",
                    "close_work_session",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId()),
                    BigDecimal.valueOf(0.95),
                    "close_session_request");
        }

        if (looksLikeSessionSummary(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "GET_SESSION_SUMMARY",
                    "get_session_summary",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId()),
                    BigDecimal.valueOf(0.90),
                    "session_summary_query");
        }

        if (looksLikeContinueWork(normalized) && context != null && context.workSessionId() != null) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("workSessionId", context.workSessionId());
            if (context.projectId() != null) {
                parameters.put("projectId", context.projectId());
            }
            parameters.put("message", input);
            return proposal(
                    "CONTINUE_WORK_SESSION",
                    "continue_work_session",
                    parameters,
                    BigDecimal.valueOf(0.99),
                    "explicit_work_session_context");
        }

        if (looksLikeActivateProject(normalized)) {
            ProjectEntity project = resolveProject(context, input);
            return proposal(
                    "ACTIVATE_PROJECT_CONTEXT",
                    "activate_project_context",
                    Map.of("projectId", project.getId()),
                    BigDecimal.valueOf(0.95),
                    "project_context_activation");
        }

        if (looksLikeProjectStatus(normalized)) {
            ProjectEntity project = resolveProject(context, input);
            return proposal(
                    "GET_PROJECT_OVERVIEW",
                    "get_project_overview",
                    Map.of("projectId", project.getId()),
                    BigDecimal.valueOf(0.95),
                    "project_status_query");
        }

        if (context != null && context.workSessionId() != null) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("workSessionId", context.workSessionId());
            if (context.projectId() != null) {
                parameters.put("projectId", context.projectId());
            }
            parameters.put("message", input);
            return proposal(
                    "CONTINUE_WORK_SESSION",
                    "continue_work_session",
                    parameters,
                    BigDecimal.valueOf(0.85),
                    "implicit_continue_on_active_session");
        }

        if (context != null && context.projectId() != null) {
            return proposal(
                    "GET_PROJECT_OVERVIEW",
                    "get_project_overview",
                    Map.of("projectId", context.projectId()),
                    BigDecimal.valueOf(0.70),
                    "implicit_active_project_query");
        }

        ProjectEntity resolvedProject = coreProjectResolver.resolveFromInputOrActive(input, null);
        if (resolvedProject != null) {
            return proposal(
                    "GET_PROJECT_OVERVIEW",
                    "get_project_overview",
                    Map.of("projectId", resolvedProject.getId()),
                    BigDecimal.valueOf(0.68),
                    "resolved_project_status_query");
        }

        throw new CoreUnknownIntentException(
                "Atenea Core could not determine a supported development intent for the current request");
    }

    private ProjectEntity resolveProject(CoreRequestContext context, String input) {
        if (context != null && context.projectId() != null) {
            return coreProjectResolver.requireById(context.projectId());
        }
        ProjectEntity project = coreProjectResolver.resolveFromInputOrActive(input, null);
        if (project != null) {
            return project;
        }
        throw new CoreUnknownIntentException(
                "Atenea Core could not determine the target project for the current request");
    }

    private WorkSessionEntity resolveSession(CoreRequestContext context, String input) {
        Long projectId = context == null ? null : context.projectId();
        WorkSessionEntity session = coreWorkSessionResolver.resolveActiveSession(
                input,
                context == null ? null : context.workSessionId(),
                projectId);
        if (session != null) {
            return session;
        }
        throw new CoreUnknownIntentException(
                "Atenea Core could not determine the target WorkSession for the current request");
    }

    private SessionDeliverableType resolveDeliverableType(String normalized) {
        if (normalized.contains("ticket")) {
            return SessionDeliverableType.WORK_TICKET;
        }
        if (normalized.contains("breakdown") || normalized.contains("desglose")) {
            return SessionDeliverableType.WORK_BREAKDOWN;
        }
        if (normalized.contains("price") || normalized.contains("budget")
                || normalized.contains("estimate") || normalized.contains("presupuesto")) {
            return SessionDeliverableType.PRICE_ESTIMATE;
        }
        throw new CoreClarificationRequiredException(new CoreClarification(
                "Please clarify which deliverable you want to generate.",
                java.util.List.of(
                        new CoreClarificationOption("DELIVERABLE_TYPE", null, SessionDeliverableType.WORK_TICKET.name()),
                        new CoreClarificationOption("DELIVERABLE_TYPE", null, SessionDeliverableType.WORK_BREAKDOWN.name()),
                        new CoreClarificationOption("DELIVERABLE_TYPE", null, SessionDeliverableType.PRICE_ESTIMATE.name())
                )));
    }

    private String extractSessionTitle(String input, String projectName) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.isBlank()) {
            return defaultSessionTitle(projectName);
        }

        String extracted = normalized
                .replaceFirst("(?i)^abre una sesi[oó]n(?: para)?\\s*", "")
                .replaceFirst("(?i)^abrir sesi[oó]n(?: para)?\\s*", "")
                .replaceFirst("(?i)^open a session(?: for)?\\s*", "")
                .replaceFirst("(?i)^create a session(?: for)?\\s*", "")
                .replaceFirst("(?i)^crea una sesi[oó]n(?: para)?\\s*", "")
                .replaceFirst("(?i)^inicia una sesi[oó]n(?: para)?\\s*", "")
                .replaceFirst("(?i)^resolve session(?: for)?\\s*", "")
                .replaceFirst("(?i)^" + java.util.regex.Pattern.quote(projectName) + "\\s*[:\\-]?\\s*", "")
                .replaceFirst("(?i)^para\\s+" + java.util.regex.Pattern.quote(projectName) + "\\s*[:\\-]?\\s*", "")
                .trim();

        extracted = extracted.replaceFirst("^[:\\-]+\\s*", "").trim();
        if (extracted.isBlank()) {
            return defaultSessionTitle(projectName);
        }
        return extracted;
    }

    private String defaultSessionTitle(String projectName) {
        return "Work on " + projectName;
    }

    private CoreInterpretationResult proposal(
            String intent,
            String capability,
            Map<String, Object> parameters,
            BigDecimal confidence,
            String detail
    ) {
        return new CoreInterpretationResult(
                new CoreIntentProposal(intent, CoreDomain.DEVELOPMENT, capability, parameters, confidence),
                CoreInterpreterSource.DETERMINISTIC,
                detail);
    }

    private boolean looksLikePortfolioStatus(String normalized) {
        boolean asksForStatus = normalized.contains("estado")
                || normalized.contains("status")
                || normalized.contains("overview")
                || normalized.contains("como estan")
                || normalized.contains("how are");
        boolean mentionsPortfolio = normalized.contains("proyectos")
                || normalized.contains("todos los proyectos")
                || normalized.contains("all projects")
                || normalized.contains("portfolio");
        return asksForStatus && mentionsPortfolio;
    }

    private boolean looksLikeProjectStatus(String normalized) {
        return (normalized.contains("estado")
                || normalized.contains("status")
                || normalized.contains("overview")
                || normalized.contains("como esta")
                || normalized.contains("how is"))
                && !looksLikePortfolioStatus(normalized)
                && !looksLikeSessionSummary(normalized);
    }

    private boolean looksLikeActivateProject(String normalized) {
        return normalized.contains("vamos a trabajar")
                || normalized.contains("trabajaremos")
                || normalized.contains("switch to")
                || normalized.contains("work on ")
                || normalized.contains("usar este proyecto")
                || normalized.contains("activa el proyecto")
                || normalized.contains("usa el proyecto");
    }

    private boolean looksLikeOpenSession(String normalized) {
        return normalized.contains("abre una sesion")
                || normalized.contains("abrir sesion")
                || normalized.contains("open a session")
                || normalized.contains("create a session")
                || normalized.contains("crea una sesion")
                || normalized.contains("inicia una sesion")
                || normalized.contains("resolve session");
    }

    private boolean looksLikeContinueWork(String normalized) {
        return normalized.contains("continua")
                || normalized.contains("continue")
                || normalized.contains("sigue")
                || normalized.contains("retoma")
                || normalized.contains("haz")
                || normalized.contains("prepara")
                || normalized.contains("fix")
                || normalized.contains("implement");
    }

    private boolean looksLikeSessionSummary(String normalized) {
        return normalized.contains("session summary")
                || normalized.contains("resumen de la sesion")
                || normalized.contains("estado de la sesion")
                || normalized.contains("como va la sesion")
                || normalized.contains("en que punto estamos")
                || normalized.contains("en que punto va")
                || normalized.contains("como vamos")
                || normalized.contains("como va el desarrollo")
                || normalized.contains("progreso")
                || normalized.contains("avance")
                || normalized.contains("avances")
                || normalized.contains("how far along")
                || normalized.contains("where are we");
    }

    private boolean looksLikePublish(String normalized) {
        return normalized.contains("publish")
                || normalized.contains("publica")
                || normalized.contains("crea la pr")
                || normalized.contains("abre la pr");
    }

    private boolean looksLikeSyncPullRequest(String normalized) {
        return normalized.contains("sync pr")
                || normalized.contains("sync pull request")
                || normalized.contains("sincroniza la pr")
                || normalized.contains("sincroniza el pull request");
    }

    private boolean looksLikeClose(String normalized) {
        return normalized.contains("close session")
                || normalized.contains("cierra la sesion")
                || normalized.contains("close it")
                || normalized.contains("cierrala");
    }

    private boolean looksLikeDeliverablesRead(String normalized) {
        return normalized.contains("deliverable")
                || normalized.contains("deliverables")
                || normalized.contains("entregable")
                || normalized.contains("entregables");
    }

    private boolean looksLikeGenerateDeliverable(String normalized) {
        return (normalized.contains("generate") || normalized.contains("genera"))
                && looksLikeDeliverablesRead(normalized);
    }

    private boolean looksLikeApproveDeliverable(String normalized) {
        return (normalized.contains("aprueba") || normalized.contains("approve"))
                && looksLikeDeliverablesRead(normalized);
    }

    private boolean looksLikeMarkPriceEstimateBilled(String normalized) {
        return (normalized.contains("facturado")
                || normalized.contains("mark billed")
                || normalized.contains("marca")
                || normalized.contains("billing reference"))
                && (normalized.contains("referencia")
                || normalized.contains("invoice")
                || normalized.contains("factura")
                || normalized.contains("billed"));
    }

    private Long extractDeliverableId(String input) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)deliverable\\s+(\\d+)|entregable\\s+(\\d+)")
                .matcher(input);
        if (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return Long.parseLong(raw);
        }
        throw new CoreInvalidContextException("Missing or invalid Core parameter: deliverableId");
    }

    private String extractBillingReference(String input) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:referencia|invoice(?: number)?|ref(?:erence)?)\\s+([A-Za-z0-9._\\-/]+)")
                .matcher(input);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        throw new CoreInvalidContextException("Missing or invalid Core parameter: billingReference");
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .replace('_', ' ')
                .replace('-', ' ')
                .trim();
    }
}
