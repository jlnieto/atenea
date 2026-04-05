package com.atenea.service.core;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CoreCapabilityArgumentResolver {

    private final CoreProjectResolver coreProjectResolver;
    private final CoreWorkSessionResolver coreWorkSessionResolver;

    public CoreCapabilityArgumentResolver(
            CoreProjectResolver coreProjectResolver,
            CoreWorkSessionResolver coreWorkSessionResolver
    ) {
        this.coreProjectResolver = coreProjectResolver;
        this.coreWorkSessionResolver = coreWorkSessionResolver;
    }

    public Map<String, Object> resolve(
            CreateCoreCommandRequest request,
            CapabilityDefinition definition,
            Map<String, Object> rawArguments,
            Map<String, Object> resolutionHints
    ) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        copyPositiveLong(rawArguments, resolved, "projectId");
        copyPositiveLong(rawArguments, resolved, "workSessionId");
        copyPositiveLong(rawArguments, resolved, "deliverableId");
        copyTrimmedText(rawArguments, resolved, "title");
        copyTrimmedText(rawArguments, resolved, "message");
        copyTrimmedText(rawArguments, resolved, "deliverableType");
        copyTrimmedText(rawArguments, resolved, "billingReference");

        if (definition.requiresParameter("projectId")) {
            ProjectEntity project = resolveProject(request, resolved, resolutionHints);
            resolved.put("projectId", project.getId());
        }

        if (definition.requiresParameter("workSessionId")) {
            WorkSessionEntity session = resolveSession(request, resolved, resolutionHints);
            resolved.put("workSessionId", session.getId());
            resolved.putIfAbsent("projectId", session.getProject().getId());
        }

        switch (definition.capability()) {
            case "continue_work_session" -> resolved.put("message", request.input().trim());
            case "create_work_session" -> resolved.putIfAbsent("title", request.input().trim());
            case "generate_session_deliverable" -> resolved.put(
                    "deliverableType",
                    resolveDeliverableType(resolved, resolutionHints, request.input()).name());
            default -> {
            }
        }

        for (CapabilityParameterDefinition parameter : definition.parameters()) {
            if (!parameter.required()) {
                continue;
            }
            Object value = resolved.get(parameter.name());
            if (value instanceof String text && text.isBlank()) {
                value = null;
            }
            if (value == null) {
                throw missingRequiredParameter(definition.capability(), parameter.name());
            }
        }
        return resolved;
    }

    private ProjectEntity resolveProject(
            CreateCoreCommandRequest request,
            Map<String, Object> resolved,
            Map<String, Object> resolutionHints
    ) {
        if (resolved.get("projectId") instanceof Number projectId) {
            return coreProjectResolver.requireById(projectId.longValue());
        }

        CoreRequestContext context = request.context();
        Long activeProjectId = context == null ? null : context.projectId();
        String candidateInput = mergeHintAndInput(text(resolutionHints, "projectName"), request.input());
        ProjectEntity project = coreProjectResolver.resolveFromInputOrActive(candidateInput, activeProjectId);
        if (project != null) {
            return project;
        }
        throw new CoreUnknownIntentException(
                "Atenea Core could not determine the target project for the current request");
    }

    private WorkSessionEntity resolveSession(
            CreateCoreCommandRequest request,
            Map<String, Object> resolved,
            Map<String, Object> resolutionHints
    ) {
        if (resolved.get("workSessionId") instanceof Number workSessionId) {
            return coreWorkSessionResolver.requireById(workSessionId.longValue());
        }

        CoreRequestContext context = request.context();
        Long activeWorkSessionId = context == null ? null : context.workSessionId();
        Long projectId = resolved.get("projectId") instanceof Number number
                ? number.longValue()
                : context == null ? null : context.projectId();
        String candidateInput = mergeHintsAndInput(
                text(resolutionHints, "projectName"),
                text(resolutionHints, "workSessionTitle"),
                request.input());
        WorkSessionEntity session = coreWorkSessionResolver.resolveActiveSession(
                candidateInput,
                activeWorkSessionId,
                projectId);
        if (session != null) {
            return session;
        }
        throw new CoreUnknownIntentException(
                "Atenea Core could not determine the target WorkSession for the current request");
    }

    private SessionDeliverableType resolveDeliverableType(
            Map<String, Object> resolved,
            Map<String, Object> resolutionHints,
            String input
    ) {
        String candidate = firstNonBlank(
                text(resolved, "deliverableType"),
                text(resolutionHints, "deliverableType"),
                input);
        if (candidate != null) {
            String normalized = normalize(candidate);
            if (normalized.contains("ticket")) {
                return SessionDeliverableType.WORK_TICKET;
            }
            if (normalized.contains("breakdown") || normalized.contains("desglose")) {
                return SessionDeliverableType.WORK_BREAKDOWN;
            }
            if (normalized.contains("price estimate")
                    || normalized.contains("estimate")
                    || normalized.contains("budget")
                    || normalized.contains("presupuesto")) {
                return SessionDeliverableType.PRICE_ESTIMATE;
            }
            try {
                return SessionDeliverableType.valueOf(candidate.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to clarification below.
            }
        }

        throw new CoreClarificationRequiredException(new CoreClarification(
                "Please clarify which deliverable you want to generate.",
                List.of(
                        new CoreClarificationOption("DELIVERABLE_TYPE", null, SessionDeliverableType.WORK_TICKET.name()),
                        new CoreClarificationOption("DELIVERABLE_TYPE", null, SessionDeliverableType.WORK_BREAKDOWN.name()),
                        new CoreClarificationOption("DELIVERABLE_TYPE", null, SessionDeliverableType.PRICE_ESTIMATE.name())
                )));
    }

    private CoreInvalidContextException missingRequiredParameter(String capability, String parameterName) {
        return new CoreInvalidContextException(
                "Missing or invalid Core parameter for capability "
                        + capability
                        + ": "
                        + parameterName);
    }

    private void copyPositiveLong(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) instanceof Number number && number.longValue() > 0) {
            target.put(key, number.longValue());
        }
    }

    private void copyTrimmedText(Map<String, Object> source, Map<String, Object> target, String key) {
        String text = text(source, key);
        if (text != null) {
            target.put(key, text);
        }
    }

    private String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    private String mergeHintAndInput(String hint, String input) {
        String merged = firstNonBlank(hint, input);
        return merged == null ? "" : merged;
    }

    private String mergeHintsAndInput(String projectHint, String workSessionHint, String input) {
        StringBuilder merged = new StringBuilder();
        appendIfPresent(merged, projectHint);
        appendIfPresent(merged, workSessionHint);
        appendIfPresent(merged, input);
        return merged.toString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .trim();
    }

    private void appendIfPresent(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
        }
        target.append(value.trim());
    }
}
