package com.atenea.service.core;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.operations.ManagedHostEntity;
import com.atenea.service.operations.OperationsHostResolver;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DefaultCoreIntentInterpreter implements CoreIntentInterpreter {

    private final CoreProjectResolver coreProjectResolver;
    private final CoreWorkSessionResolver coreWorkSessionResolver;
    private final OperationsHostResolver operationsHostResolver;

    public DefaultCoreIntentInterpreter(
            CoreProjectResolver coreProjectResolver,
            CoreWorkSessionResolver coreWorkSessionResolver,
            OperationsHostResolver operationsHostResolver
    ) {
        this.coreProjectResolver = coreProjectResolver;
        this.coreWorkSessionResolver = coreWorkSessionResolver;
        this.operationsHostResolver = operationsHostResolver;
    }

    @Override
    public CoreInterpretationResult interpret(CreateCoreCommandRequest request) {
        CoreRequestContext context = request.context();
        String input = request.input().trim();
        String normalized = normalize(input);

        if (looksLikeOperationsIncidentClose(normalized)) {
            return proposal(
                    "CLOSE_OPERATIONS_INCIDENT",
                    CoreDomain.OPERATIONS,
                    "close_operations_incident",
                    Map.of("incidentId", extractIncidentId(input)),
                    BigDecimal.valueOf(0.94),
                    "operations_incident_close_request");
        }

        if (looksLikeOperationsIncidentList(normalized)) {
            return proposal(
                    "LIST_OPERATIONS_INCIDENTS",
                    CoreDomain.OPERATIONS,
                    "list_operations_incidents",
                    Map.of(),
                    BigDecimal.valueOf(0.93),
                    "operations_incident_list_request");
        }

        if (looksLikeHostList(normalized)) {
            return proposal(
                    "LIST_HOSTS",
                    CoreDomain.OPERATIONS,
                    "list_hosts",
                    Map.of(),
                    BigDecimal.valueOf(0.93),
                    "operations_host_list_request");
        }

        if (looksLikeApacheRecovery(normalized)) {
            ManagedHostEntity host = resolveOperationsHost(input);
            return proposal(
                    "RECOVER_APACHE_HUNG_PROCESSES",
                    CoreDomain.OPERATIONS,
                    "recover_apache_hung_processes",
                    Map.of("hostId", host.getId()),
                    BigDecimal.valueOf(0.96),
                    "apache_recovery_request");
        }

        if (looksLikeServiceCheck(normalized)) {
            ManagedHostEntity host = resolveOperationsHost(input);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("hostId", host.getId());
            parameters.put("serviceName", normalized.contains("apache") ? "apache" : "apache");
            return proposal(
                    "CHECK_SERVICE",
                    CoreDomain.OPERATIONS,
                    "check_service",
                    parameters,
                    BigDecimal.valueOf(0.93),
                    "operations_service_check_request");
        }

        if (looksLikeHostStatus(normalized)) {
            ManagedHostEntity host = resolveOperationsHost(input);
            return proposal(
                    "GET_HOST_STATUS",
                    CoreDomain.OPERATIONS,
                    "get_host_status",
                    Map.of("hostId", host.getId()),
                    BigDecimal.valueOf(0.92),
                    "operations_host_status_request");
        }

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

        if (looksLikeDatabaseRefresh(normalized)) {
            ProjectEntity project = resolveProject(context, input);
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("projectId", project.getId());
            parameters.put("projectName", project.getName());
            return proposal(
                    "REFRESH_PROJECT_DATABASE",
                    "refresh_project_database",
                    parameters,
                    BigDecimal.valueOf(0.96),
                    "explicit_project_database_refresh_request");
        }

        if (looksLikeProjectVerification(normalized)) {
            WorkSessionEntity session = tryResolveSession(context, input);
            if (session != null) {
                return proposal(
                        "RUN_PROJECT_VERIFICATION",
                        "run_project_verification",
                        Map.of(
                                "projectId", session.getProject().getId(),
                                "workSessionId", session.getId()),
                        BigDecimal.valueOf(0.95),
                        "project_verification_request");
            }
            ProjectEntity project = resolveProject(context, input);
            return proposal(
                    "RUN_PROJECT_VERIFICATION",
                    "run_project_verification",
                    Map.of("projectId", project.getId()),
                    BigDecimal.valueOf(0.91),
                    "project_verification_request");
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
                            "workSessionId", session.getId(),
                            "forceClosePendingDeliverables", shouldForceClosePendingDeliverables(normalized)),
                    BigDecimal.valueOf(0.95),
                    "close_session_request");
        }

        if (looksLikeLatestSessionResponse(normalized)) {
            WorkSessionEntity session = resolveSession(context, input);
            return proposal(
                    "GET_LATEST_SESSION_RESPONSE",
                    "get_latest_session_response",
                    Map.of(
                            "projectId", session.getProject().getId(),
                            "workSessionId", session.getId()),
                    BigDecimal.valueOf(0.94),
                    "latest_session_response_query");
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

    private WorkSessionEntity tryResolveSession(CoreRequestContext context, String input) {
        Long projectId = context == null ? null : context.projectId();
        return coreWorkSessionResolver.resolveActiveSession(
                input,
                context == null ? null : context.workSessionId(),
                projectId);
    }

    private ManagedHostEntity resolveOperationsHost(String input) {
        ManagedHostEntity host = operationsHostResolver.resolveFromInput(input);
        if (host != null) {
            return host;
        }
        throw new CoreUnknownIntentException(
                "Atenea Core could not determine the target managed host for the current request");
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

    private CoreInterpretationResult proposal(
            String intent,
            CoreDomain domain,
            String capability,
            Map<String, Object> parameters,
            BigDecimal confidence,
            String detail
    ) {
        return new CoreInterpretationResult(
                new CoreIntentProposal(intent, domain, capability, parameters, confidence),
                CoreInterpreterSource.DETERMINISTIC,
                detail);
    }

    private boolean looksLikeHostList(String normalized) {
        return (normalized.contains("servidores") || normalized.contains("vps") || normalized.contains("hosts"))
                && (normalized.contains("monitorizados")
                || normalized.contains("gestionados")
                || normalized.contains("lista")
                || normalized.contains("tengo"));
    }

    private boolean looksLikeHostStatus(String normalized) {
        return (normalized.contains("revisa")
                || normalized.contains("comprueba")
                || normalized.contains("estado")
                || normalized.contains("como esta")
                || normalized.contains("status"))
                && mentionsOperationsTarget(normalized)
                && !normalized.contains("apache");
    }

    private boolean looksLikeServiceCheck(String normalized) {
        return normalized.contains("apache")
                && (normalized.contains("revisa")
                || normalized.contains("comprueba")
                || normalized.contains("estado")
                || normalized.contains("como esta")
                || normalized.contains("diagnostica"));
    }

    private boolean looksLikeApacheRecovery(String normalized) {
        return normalized.contains("apache")
                && (normalized.contains("recupera")
                || normalized.contains("arregla")
                || normalized.contains("reinicia")
                || normalized.contains("restart")
                || normalized.contains("recover")
                || normalized.contains("colgado")
                || normalized.contains("colgados"));
    }

    private boolean looksLikeOperationsIncidentList(String normalized) {
        return (normalized.contains("incidencias") || normalized.contains("incidentes") || normalized.contains("alertas"))
                && (normalized.contains("servidor")
                || normalized.contains("servidores")
                || normalized.contains("operaciones")
                || normalized.contains("abiertas"));
    }

    private boolean looksLikeOperationsIncidentClose(String normalized) {
        return (normalized.contains("cierra") || normalized.contains("close"))
                && (normalized.contains("incidencia") || normalized.contains("incidente"));
    }

    private boolean mentionsOperationsTarget(String normalized) {
        return normalized.contains("dedicado")
                || normalized.contains("servidor")
                || normalized.contains("vps")
                || normalized.contains("host")
                || normalized.contains("webs")
                || normalized.contains("apache");
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
                || normalized.contains("usa el proyecto")
                || normalized.contains("cambia al proyecto")
                || normalized.contains("cambia a proyecto")
                || normalized.contains("cambia a ")
                || normalized.contains("cambiar a ");
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

    private boolean looksLikeProjectVerification(String normalized) {
        boolean asksToVerify = normalized.contains("verifica")
                || normalized.contains("validar")
                || normalized.contains("valida")
                || normalized.contains("comprueba")
                || normalized.contains("pasa las pruebas")
                || normalized.contains("run tests")
                || normalized.contains("test");
        boolean mentionsRuntimeOrDelivery = normalized.contains("pruebas")
                || normalized.contains("navegador")
                || normalized.contains("browser")
                || normalized.contains("smoke")
                || normalized.contains("runtime")
                || normalized.contains("desplegar")
                || normalized.contains("deploy")
                || normalized.contains("antes de publicar")
                || normalized.contains("antes de desplegar");
        return asksToVerify && mentionsRuntimeOrDelivery;
    }

    private boolean looksLikeDatabaseRefresh(String normalized) {
        boolean asksRefresh = normalized.contains("actualiza")
                || normalized.contains("reemplaza")
                || normalized.contains("refresca");
        boolean mentionsDatabase = normalized.contains(" bd")
                || normalized.startsWith("bd ")
                || normalized.endsWith(" bd")
                || normalized.contains("base de datos");
        return asksRefresh && mentionsDatabase;
    }

    private boolean looksLikeLatestSessionResponse(String normalized) {
        if (normalized.contains("estado de codex")
                || normalized.contains("status de codex")
                || normalized.contains("codex status")
                || normalized.contains("como va codex")
                || normalized.contains("codex sigue trabajando")) {
            return true;
        }
        boolean mentionsResponse = normalized.contains("respuesta")
                || normalized.contains("respondido")
                || normalized.contains("contestado")
                || normalized.contains("ha dicho")
                || normalized.contains("dijo")
                || normalized.contains("ultimo")
                || normalized.contains("ultima")
                || normalized.contains("lee")
                || normalized.contains("leeme");
        boolean mentionsCodex = normalized.contains("codex");
        boolean asksForLatestAnswer = normalized.contains("ultima respuesta")
                || normalized.contains("ultimo que dijo")
                || normalized.contains("que ha dicho")
                || normalized.contains("ha contestado")
                || normalized.contains("ha respondido")
                || normalized.contains("leer respuesta");
        return mentionsResponse && (mentionsCodex || asksForLatestAnswer);
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

    private boolean shouldForceClosePendingDeliverables(String normalized) {
        return normalized.contains("igualmente")
                || normalized.contains("de todos modos")
                || normalized.contains("aunque falten entregables")
                || normalized.contains("sin generar entregables");
    }

    private boolean looksLikeDeliverablesRead(String normalized) {
        if (looksLikeClose(normalized)) {
            return false;
        }
        return normalized.contains("deliverable")
                || normalized.contains("deliverables")
                || normalized.contains("entregable")
                || normalized.contains("entregables");
    }

    private boolean looksLikeGenerateDeliverable(String normalized) {
        return (normalized.contains("generate")
                || normalized.contains("genera")
                || normalized.contains("prepara")
                || normalized.contains("create"))
                && mentionsDeliverableSubject(normalized);
    }

    private boolean looksLikeApproveDeliverable(String normalized) {
        return (normalized.contains("aprueba") || normalized.contains("approve"))
                && looksLikeDeliverablesRead(normalized);
    }

    private boolean mentionsDeliverableSubject(String normalized) {
        return looksLikeDeliverablesRead(normalized)
                || normalized.contains("ticket de trabajo")
                || normalized.contains("work ticket")
                || normalized.contains("desglose")
                || normalized.contains("work breakdown")
                || normalized.contains("price estimate")
                || normalized.contains("presupuesto")
                || normalized.contains("budget")
                || normalized.contains("estimate");
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

    private Long extractIncidentId(String input) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:incidencia|incidente)\\s+(\\d+)")
                .matcher(input);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new CoreInvalidContextException("Missing or invalid Core parameter: incidentId");
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
