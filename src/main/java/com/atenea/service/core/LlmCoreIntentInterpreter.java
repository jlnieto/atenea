package com.atenea.service.core;

import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import com.atenea.codexappserver.CodexAppServerExecutionResult;
import com.atenea.codexappserver.CodexAppServerProperties;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreInterpreterSource;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LlmCoreIntentInterpreter {

    private static final Collection<WorkSessionStatus> ACTIVE_SESSION_STATUSES = List.of(
            WorkSessionStatus.OPEN,
            WorkSessionStatus.CLOSING);

    private static final String INTERPRETATION_PROMPT = """
            You are routing one Atenea Core command to exactly one capability from the provided catalog.

            Return exactly one JSON object and nothing else.
            Do not use Markdown.
            Do not use code fences.

            Rules:
            - Only choose a capability that exists in the provided capability catalog.
            - Treat the catalog as the source of truth for what each capability means.
            - Do not invent projectId or workSessionId values that are not present in the provided context.
            - Prefer selecting the right capability over guessing identifiers.
            - Use arguments only for values that are explicit or strongly inferable.
            - Use resolutionHints for names or labels that help the backend resolve the final target.
            - If the operator asks what point the development is at, latest progress, blocker, advancement, next step, or "en que punto estamos", prefer get_session_summary when an active session is inferable.
            - If the operator asks for administrative or structural status of one project, prefer get_project_overview.
            - If the operator asks about projects in plural or the whole portfolio, prefer list_projects_overview.
            - If the operator is instructing execution in an active session, prefer continue_work_session.
            - If the command is ambiguous, set needsClarification to true and explain the ambiguity in reasoning.
            - If no supported capability fits confidently, return capability "unknown" and domain "UNKNOWN".
            - confidence must be a number between 0 and 1.

            Required JSON shape:
            {
              "domain": "DEVELOPMENT",
              "capability": "create_work_session",
              "confidence": 0.0,
              "arguments": {
                "projectId": 0,
                "workSessionId": 0,
                "title": "",
                "message": "",
                "deliverableType": ""
              },
              "resolutionHints": {
                "projectName": "",
                "workSessionTitle": "",
                "deliverableType": ""
              },
              "needsClarification": false,
              "missing": [],
              "reasoning": ""
            }

            Routing context:
            """;

    private final CodexAppServerClient codexAppServerClient;
    private final CodexAppServerProperties codexAppServerProperties;
    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final ObjectMapper objectMapper;
    private final CoreLlmProperties coreLlmProperties;
    private final CoreCapabilityRegistry coreCapabilityRegistry;
    private final CoreCapabilityArgumentResolver coreCapabilityArgumentResolver;

    public LlmCoreIntentInterpreter(
            CodexAppServerClient codexAppServerClient,
            CodexAppServerProperties codexAppServerProperties,
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            ObjectMapper objectMapper,
            CoreLlmProperties coreLlmProperties,
            CoreCapabilityRegistry coreCapabilityRegistry,
            CoreCapabilityArgumentResolver coreCapabilityArgumentResolver
    ) {
        this.codexAppServerClient = codexAppServerClient;
        this.codexAppServerProperties = codexAppServerProperties;
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.objectMapper = objectMapper;
        this.coreLlmProperties = coreLlmProperties;
        this.coreCapabilityRegistry = coreCapabilityRegistry;
        this.coreCapabilityArgumentResolver = coreCapabilityArgumentResolver;
    }

    public CoreInterpretationResult interpret(CreateCoreCommandRequest request) {
        if (!coreLlmProperties.isEnabled()) {
            throw new IllegalStateException("Core LLM interpreter is disabled");
        }

        String repoPath = resolvePromptRepoPath(request);
        String prompt = buildPrompt(request);

        CodexAppServerExecutionResult result;
        try {
            result = codexAppServerClient.execute(new CodexAppServerExecutionRequest(repoPath, prompt));
        } catch (Exception exception) {
            throw new IllegalStateException("Core LLM interpretation failed", exception);
        }

        if (result.status() != CodexAppServerExecutionResult.Status.COMPLETED || result.finalAnswer() == null) {
            throw new IllegalStateException("Core LLM did not return a completed structured interpretation");
        }

        return toInterpretationResult(request, parseStructuredAnswer(result.finalAnswer()));
    }

    private String resolvePromptRepoPath(CreateCoreCommandRequest request) {
        if (request.context() != null && request.context().workSessionId() != null) {
            WorkSessionEntity session = workSessionRepository.findWithProjectById(request.context().workSessionId())
                    .orElse(null);
            if (session != null) {
                return workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
            }
        }

        if (request.context() != null && request.context().projectId() != null) {
            ProjectEntity project = projectRepository.findById(request.context().projectId()).orElse(null);
            if (project != null) {
                return workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(project.getRepoPath());
            }
        }

        return codexAppServerProperties.getCwd();
    }

    private String buildPrompt(CreateCoreCommandRequest request) {
        Map<String, Object> promptContext = new LinkedHashMap<>();
        promptContext.put("promptVersion", coreLlmProperties.getPromptVersion());
        promptContext.put("operatorInput", request.input().trim());
        promptContext.put("explicitContext", request.context());
        promptContext.put("capabilityCatalog", coreCapabilityRegistry.listEnabledDefinitions(CoreDomain.DEVELOPMENT).stream()
                .map(this::toCatalogPromptView)
                .toList());
        promptContext.put("availableProjects", projectRepository.findAll().stream()
                .map(project -> Map.of(
                        "projectId", project.getId(),
                        "name", project.getName(),
                        "description", project.getDescription() == null ? "" : project.getDescription()))
                .toList());
        promptContext.put("activeWorkSessions", workSessionRepository
                .findByStatusInOrderByLastActivityAtDesc(ACTIVE_SESSION_STATUSES)
                .stream()
                .map(session -> Map.of(
                        "workSessionId", session.getId(),
                        "projectId", session.getProject().getId(),
                        "projectName", session.getProject().getName(),
                        "title", session.getTitle(),
                        "status", session.getStatus().name()))
                .toList());
        return INTERPRETATION_PROMPT + writeJson(promptContext);
    }

    private JsonNode parseStructuredAnswer(String answer) {
        String normalized = stripCodeFences(answer).trim();
        try {
            return objectMapper.readTree(normalized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Core LLM returned non-JSON output", exception);
        }
    }

    private CoreInterpretationResult toInterpretationResult(CreateCoreCommandRequest request, JsonNode node) {
        String domain = text(node, "domain");
        String capability = text(node, "capability");
        if (!"DEVELOPMENT".equalsIgnoreCase(domain) || capability == null || "unknown".equalsIgnoreCase(capability)) {
            throw new CoreUnknownIntentException("Core LLM could not determine a supported development capability");
        }

        CapabilityDefinition definition = coreCapabilityRegistry.requireEnabled(CoreDomain.DEVELOPMENT, capability);
        Map<String, Object> rawArguments = readObjectNode(node.path("arguments"));
        if (rawArguments.isEmpty()) {
            rawArguments = readObjectNode(node.path("parameters"));
        }
        Map<String, Object> resolutionHints = readObjectNode(node.path("resolutionHints"));
        Map<String, Object> resolvedArguments = coreCapabilityArgumentResolver.resolve(
                request,
                definition,
                rawArguments,
                resolutionHints);
        BigDecimal confidence = node.hasNonNull("confidence")
                ? node.get("confidence").decimalValue()
                : BigDecimal.valueOf(0.50);
        String reasoning = text(node, "reasoning");
        boolean needsClarification = node.path("needsClarification").asBoolean(false);
        String interpretationDetail = buildInterpretationDetail(reasoning, needsClarification, resolutionHints);

        return new CoreInterpretationResult(
                new CoreIntentProposal(
                        capability.toUpperCase(Locale.ROOT),
                        CoreDomain.DEVELOPMENT,
                        capability,
                        resolvedArguments,
                        confidence),
                CoreInterpreterSource.LLM,
                interpretationDetail);
    }

    private Map<String, Object> toCatalogPromptView(CapabilityDefinition definition) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("domain", definition.domain().name());
        view.put("capability", definition.capability());
        view.put("riskLevel", definition.riskLevel().name());
        view.put("requiresConfirmation", definition.requiresConfirmation());
        view.put("summary", definition.summary());
        view.put("whenToUse", definition.whenToUse());
        view.put("whenNotToUse", definition.whenNotToUse());
        view.put("parameters", definition.parameters().stream()
                .map(parameter -> Map.of(
                        "name", parameter.name(),
                        "type", parameter.type(),
                        "required", parameter.required(),
                        "description", parameter.description()))
                .toList());
        view.put("examples", definition.examples().stream()
                .map(example -> Map.of(
                        "input", example.input(),
                        "explanation", example.explanation()))
                .toList());
        return view;
    }

    private Map<String, Object> readObjectNode(JsonNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!node.isObject()) {
            return values;
        }
        putLong(values, "projectId", node.get("projectId"));
        putLong(values, "workSessionId", node.get("workSessionId"));
        putLong(values, "deliverableId", node.get("deliverableId"));
        putText(values, "title", node.get("title"));
        putText(values, "message", node.get("message"));
        putText(values, "deliverableType", node.get("deliverableType"));
        putText(values, "billingReference", node.get("billingReference"));
        putText(values, "projectName", node.get("projectName"));
        putText(values, "workSessionTitle", node.get("workSessionTitle"));
        return values;
    }

    private String buildInterpretationDetail(String reasoning, boolean needsClarification, Map<String, Object> resolutionHints) {
        StringBuilder detail = new StringBuilder("llm_capability_router");
        if (needsClarification) {
            detail.append(":clarification");
        }
        if (resolutionHints != null && !resolutionHints.isEmpty()) {
            detail.append(":hinted");
        }
        String normalizedReasoning = reasoning == null ? null : reasoning.trim();
        if (normalizedReasoning != null && !normalizedReasoning.isBlank()) {
            if (normalizedReasoning.length() > 120) {
                normalizedReasoning = normalizedReasoning.substring(0, 117).trim() + "...";
            }
            detail.append(":").append(normalizedReasoning.replaceAll("\\s+", " "));
        }
        return detail.toString();
    }

    private void putLong(Map<String, Object> parameters, String key, JsonNode value) {
        if (value != null && value.isNumber()) {
            long longValue = value.longValue();
            if (longValue > 0) {
                parameters.put(key, longValue);
            }
        }
    }

    private void putText(Map<String, Object> parameters, String key, JsonNode value) {
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            parameters.put(key, value.asText().trim());
        }
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() ? value.asText().trim() : null;
    }

    private String stripCodeFences(String answer) {
        String trimmed = answer.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || closingFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, closingFence);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Core LLM prompt context", exception);
        }
    }
}
