package com.atenea.service.core;

import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreRiskLevel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CoreCapabilityRegistry {

    private final Map<String, CapabilityDefinition> definitions = Map.ofEntries(
            Map.entry(key(CoreDomain.DEVELOPMENT, "list_projects_overview"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "list_projects_overview",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Portfolio-wide development overview of the registered projects.",
                            "Use when the operator asks for the state of multiple projects, the whole portfolio, or all projects in plural.",
                            "Do not use when the operator is singling out one concrete project or asking for the current point of a specific work session.",
                            List.of(),
                            List.of(
                                    new CapabilityExample("dime el estado de los proyectos",
                                            "The operator is asking for a global overview across projects."),
                                    new CapabilityExample("how are all projects going",
                                            "The operator wants a portfolio-wide status.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_project_overview"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "get_project_overview",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Administrative or structural overview of one project.",
                            "Use when the operator asks for the status of a single project, its active session, branch, or pull request state.",
                            "Do not use when the operator is asking for progress of the current work, what has been done, blockers, or next step. That should be get_session_summary.",
                            List.of(requiredLong("projectId", "Target project identifier.")),
                            List.of(
                                    new CapabilityExample("estado del proyecto pruebas inicial",
                                            "The operator asks for one project's status."),
                                    new CapabilityExample("how is the pruebas inicial project",
                                            "Singular project status question.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "activate_project_context"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "activate_project_context",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Set one project as the active context for future commands.",
                            "Use when the operator says we will work on a project or wants to switch focus to a project.",
                            "Do not use for status questions or to continue a work session.",
                            List.of(requiredLong("projectId", "Project to activate as current context.")),
                            List.of(
                                    new CapabilityExample("vamos a trabajar en pruebas inicial",
                                            "This switches the active project context.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "create_work_session"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "create_work_session",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Open or resolve the work session for a project.",
                            "Use when the operator asks to start, open, create, or resolve a session for a project.",
                            "Do not use for status-only questions or to continue an already active session with a concrete task.",
                            List.of(
                                    requiredLong("projectId", "Project where the new or reused work session should belong."),
                                    optionalText("title", "Human title for the work session.")),
                            List.of(
                                    new CapabilityExample("abre una sesión para pruebas inicial",
                                            "The operator wants to open a work session."),
                                    new CapabilityExample("create a session for Atenea Core",
                                            "The operator wants to start work on a project.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "continue_work_session"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "continue_work_session",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Send a new instruction to the current work session so Codex continues execution.",
                            "Use when the operator asks Atenea/Codex to keep working, implement, fix, continue, or prepare something in an active session.",
                            "Do not use for passive status questions that only ask for progress or overview.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known."),
                                    requiredText("message", "Instruction that should be sent into the work session.")),
                            List.of(
                                    new CapabilityExample("continúa con la sesión y prepara el siguiente paso",
                                            "This is an execution request for the active session.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "publish_work_session"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "publish_work_session",
                            CoreRiskLevel.SAFE_WRITE,
                            true,
                            true,
                            "Publish the current work session to a pull request.",
                            "Use when the operator explicitly asks to publish, create the PR, or open the PR for the active work.",
                            "Do not use for read-only questions about PR state.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known.")),
                            List.of(
                                    new CapabilityExample("publica la sesión actual",
                                            "This publishes the current work session to GitHub.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "sync_work_session_pull_request"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "sync_work_session_pull_request",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Refresh the pull request state for the work session.",
                            "Use when the operator asks to sync or refresh PR state.",
                            "Do not use to publish a PR or to ask whether a PR exists in general.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known.")),
                            List.of(
                                    new CapabilityExample("sincroniza la pr de la sesión",
                                            "This refreshes the PR state for one work session.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_session_summary"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "get_session_summary",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Development progress summary for one active work session.",
                            "Use when the operator asks what point the work is at, how progress is going, latest advancement, blocker, or next step.",
                            "Do not use for project-wide administrative status questions or portfolio overview questions.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known.")),
                            List.of(
                                    new CapabilityExample("del proyecto pruebas inicial, dime en qué punto estamos",
                                            "This asks for development progress, not project admin status."),
                                    new CapabilityExample("cómo va el desarrollo de pruebas inicial",
                                            "This is a session-progress question.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_latest_session_response"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "get_latest_session_response",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Read the latest Codex or Atenea answer stored in one active work session.",
                            "Use when the operator asks whether Codex has answered, what Codex said, or asks to read the latest response.",
                            "Do not use for new execution instructions; those should use continue_work_session.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known.")),
                            List.of(
                                    new CapabilityExample("te ha contestado Codex",
                                            "This checks and reads the latest answer in the active work session."),
                                    new CapabilityExample("lee la última respuesta de Codex",
                                            "This reads the latest stored Codex response without creating a new turn.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "get_session_deliverables"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "get_session_deliverables",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "List deliverables associated with a work session.",
                            "Use when the operator asks for deliverables of the session.",
                            "Do not use to generate a new deliverable.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known.")),
                            List.of(
                                    new CapabilityExample("enséñame los entregables de la sesión",
                                            "This reads deliverables for a session.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "generate_session_deliverable"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "generate_session_deliverable",
                            CoreRiskLevel.SAFE_WRITE,
                            false,
                            true,
                            "Generate one deliverable for a work session.",
                            "Use when the operator explicitly asks to generate a ticket, work breakdown, or price estimate.",
                            "Do not use if the operator only wants to inspect existing deliverables.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known."),
                                    requiredText("deliverableType", "One of WORK_TICKET, WORK_BREAKDOWN, PRICE_ESTIMATE.")),
                            List.of(
                                    new CapabilityExample("genera el ticket de trabajo",
                                            "This should map to WORK_TICKET."),
                                    new CapabilityExample("prepara un presupuesto para la sesión actual",
                                            "This should map to PRICE_ESTIMATE.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "approve_session_deliverable"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "approve_session_deliverable",
                            CoreRiskLevel.SAFE_WRITE,
                            true,
                            true,
                            "Approve one deliverable as the active baseline for a work session.",
                            "Use when the operator explicitly asks to approve a deliverable or mark one generated deliverable as approved.",
                            "Do not use for read-only deliverable inspection or to generate a new deliverable.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known."),
                                    requiredLong("deliverableId", "Deliverable identifier that should be approved.")),
                            List.of(
                                    new CapabilityExample("aprueba el deliverable 301",
                                            "This approves one deliverable version as baseline.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "mark_price_estimate_billed"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "mark_price_estimate_billed",
                            CoreRiskLevel.SAFE_WRITE,
                            true,
                            true,
                            "Mark an approved price estimate as billed with a billing reference.",
                            "Use when the operator explicitly asks to mark the approved price estimate as billed or to record a billing reference.",
                            "Do not use to inspect billing state or to approve a deliverable.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known."),
                                    requiredLong("deliverableId", "Approved price estimate deliverable identifier."),
                                    requiredText("billingReference", "Billing reference or invoice number to persist.")),
                            List.of(
                                    new CapabilityExample("marca el deliverable 501 como facturado con referencia INV-1",
                                            "This marks the approved price estimate as billed.")))),
            Map.entry(key(CoreDomain.DEVELOPMENT, "close_work_session"),
                    definition(
                            CoreDomain.DEVELOPMENT,
                            "close_work_session",
                            CoreRiskLevel.SAFE_WRITE,
                            true,
                            true,
                            "Close the active work session.",
                            "Use when the operator explicitly asks to close the session.",
                            "Do not use when the operator only asks for status or wants to publish.",
                            List.of(
                                    requiredLong("workSessionId", "Target work session identifier."),
                                    optionalLong("projectId", "Owning project identifier when known."),
                                    optionalBoolean("forceClosePendingDeliverables",
                                            "When true, close without auto-generating pending core deliverables first.")),
                            List.of(
                                    new CapabilityExample("cierra la sesión",
                                            "This closes the current work session.")))),
            Map.entry(key(CoreDomain.OPERATIONS, "list_hosts"),
                    definition(
                            CoreDomain.OPERATIONS,
                            "list_hosts",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "List the managed servers and VPS registered for operational work.",
                            "Use when the operator asks what servers, VPS or managed hosts Atenea can operate.",
                            "Do not use for development project status.",
                            List.of(),
                            List.of(new CapabilityExample("qué servidores tengo monitorizados",
                                    "The operator asks for managed operations hosts.")))),
            Map.entry(key(CoreDomain.OPERATIONS, "get_host_status"),
                    definition(
                            CoreDomain.OPERATIONS,
                            "get_host_status",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Run a host-level diagnostic and external website checks for one managed server.",
                            "Use when the operator asks to review a server, dedicated host or VPS health.",
                            "Do not use when the operator asks to modify repository code.",
                            List.of(requiredLong("hostId", "Target managed host identifier.")),
                            List.of(new CapabilityExample("revisa el dedicado",
                                    "The operator asks for host operational status.")))),
            Map.entry(key(CoreDomain.OPERATIONS, "check_service"),
                    definition(
                            CoreDomain.OPERATIONS,
                            "check_service",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "Run a remote diagnostic for a service on a managed host.",
                            "Use when the operator asks how Apache or another service is doing.",
                            "Do not use when the operator asks to recover or restart the service.",
                            List.of(
                                    requiredLong("hostId", "Target managed host identifier."),
                                    optionalText("serviceName", "Service name, such as apache.")),
                            List.of(new CapabilityExample("cómo está apache en el dedicado",
                                    "The operator asks for an Apache diagnostic.")))),
            Map.entry(key(CoreDomain.OPERATIONS, "recover_apache_hung_processes"),
                    definition(
                            CoreDomain.OPERATIONS,
                            "recover_apache_hung_processes",
                            CoreRiskLevel.DESTRUCTIVE,
                            true,
                            true,
                            "Execute the controlled Apache recovery runbook for hung Apache processes.",
                            "Use when the operator explicitly asks to recover, fix or restart Apache because websites are slow or Apache is stuck.",
                            "Do not use for read-only status checks.",
                            List.of(requiredLong("hostId", "Target managed host identifier.")),
                            List.of(new CapabilityExample("recupera apache en el dedicado",
                                    "This executes the confirmed Apache recovery runbook.")))),
            Map.entry(key(CoreDomain.OPERATIONS, "list_operations_incidents"),
                    definition(
                            CoreDomain.OPERATIONS,
                            "list_operations_incidents",
                            CoreRiskLevel.READ,
                            false,
                            true,
                            "List currently active operations incidents.",
                            "Use when the operator asks what operational incidents or server alerts are open.",
                            "Do not use for development work-session blockers.",
                            List.of(),
                            List.of(new CapabilityExample("qué incidencias de servidores hay abiertas",
                                    "The operator wants active operations incidents.")))),
            Map.entry(key(CoreDomain.OPERATIONS, "close_operations_incident"),
                    definition(
                            CoreDomain.OPERATIONS,
                            "close_operations_incident",
                            CoreRiskLevel.SAFE_WRITE,
                            true,
                            true,
                            "Close an operations incident manually.",
                            "Use when the operator explicitly asks to close an operations incident.",
                            "Do not use to recover a service.",
                            List.of(requiredLong("incidentId", "Target operations incident identifier.")),
                            List.of(new CapabilityExample("cierra la incidencia 12",
                                    "The operator wants to close one operations incident.")))),
            Map.entry(key(CoreDomain.COMMUNICATIONS, "read_latest_email"),
                    new CapabilityDefinition(CoreDomain.COMMUNICATIONS, "read_latest_email", CoreRiskLevel.READ, false, false)),
            Map.entry(key(CoreDomain.COMMUNICATIONS, "draft_email"),
                    new CapabilityDefinition(CoreDomain.COMMUNICATIONS, "draft_email", CoreRiskLevel.READ, false, false)),
            Map.entry(key(CoreDomain.COMMUNICATIONS, "send_email"),
                    new CapabilityDefinition(CoreDomain.COMMUNICATIONS, "send_email", CoreRiskLevel.SAFE_WRITE, true, false))
    );

    public CapabilityDefinition requireEnabled(CoreDomain domain, String capability) {
        CapabilityDefinition definition = definitions.get(key(domain, capability));
        if (definition == null) {
            throw new CoreCapabilityDisabledException("Capability is not registered: " + domain + ":" + capability);
        }
        if (!definition.enabled()) {
            throw new CoreCapabilityDisabledException("Capability is not enabled: " + domain + ":" + capability);
        }
        return definition;
    }

    public List<CapabilityDefinition> listEnabledDefinitions(CoreDomain domain) {
        return definitions.values().stream()
                .filter(definition -> definition.domain() == domain)
                .filter(CapabilityDefinition::enabled)
                .sorted((left, right) -> left.capability().compareTo(right.capability()))
                .toList();
    }

    private static String key(CoreDomain domain, String capability) {
        return domain.name() + ":" + capability;
    }

    private static CapabilityDefinition definition(
            CoreDomain domain,
            String capability,
            CoreRiskLevel riskLevel,
            boolean requiresConfirmation,
            boolean enabled,
            String summary,
            String whenToUse,
            String whenNotToUse,
            List<CapabilityParameterDefinition> parameters,
            List<CapabilityExample> examples
    ) {
        return new CapabilityDefinition(
                domain,
                capability,
                riskLevel,
                requiresConfirmation,
                enabled,
                summary,
                whenToUse,
                whenNotToUse,
                parameters,
                examples);
    }

    private static CapabilityParameterDefinition requiredLong(String name, String description) {
        return new CapabilityParameterDefinition(name, "long", true, description);
    }

    private static CapabilityParameterDefinition optionalLong(String name, String description) {
        return new CapabilityParameterDefinition(name, "long", false, description);
    }

    private static CapabilityParameterDefinition requiredText(String name, String description) {
        return new CapabilityParameterDefinition(name, "string", true, description);
    }

    private static CapabilityParameterDefinition optionalText(String name, String description) {
        return new CapabilityParameterDefinition(name, "string", false, description);
    }

    private static CapabilityParameterDefinition optionalBoolean(String name, String description) {
        return new CapabilityParameterDefinition(name, "boolean", false, description);
    }
}
