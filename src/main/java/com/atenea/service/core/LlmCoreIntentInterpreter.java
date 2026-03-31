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
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LlmCoreIntentInterpreter {

    private static final Collection<WorkSessionStatus> ACTIVE_SESSION_STATUSES = List.of(
            WorkSessionStatus.OPEN,
            WorkSessionStatus.CLOSING);

    private static final String INTERPRETATION_PROMPT = """
            You are classifying an Atenea Core command.

            Return exactly one JSON object and nothing else.
            Do not use Markdown.
            Do not use code fences.

            Allowed values:
            - intent: CREATE_WORK_SESSION, CONTINUE_WORK_SESSION, UNKNOWN
            - domain: DEVELOPMENT, UNKNOWN
            - capability: create_work_session, continue_work_session, unknown

            Rules:
            - Only classify development-domain intents.
            - Use the provided projects and active sessions to resolve ids when possible.
            - If you cannot determine a valid development action confidently, return UNKNOWN.
            - For CONTINUE_WORK_SESSION, prefer a concrete workSessionId when one is inferable.
            - For CREATE_WORK_SESSION, prefer a concrete projectId when one is inferable.
            - Put identifiers inside parameters.
            - Do not invent projectId or workSessionId values not present in the provided context.
            - confidence must be a number between 0 and 1.

            Required JSON shape:
            {
              "intent": "...",
              "domain": "...",
              "capability": "...",
              "parameters": {
                "projectId": 0,
                "workSessionId": 0,
                "title": ""
              },
              "confidence": 0.0
            }

            Classification context:
            """;

    private final CodexAppServerClient codexAppServerClient;
    private final CodexAppServerProperties codexAppServerProperties;
    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final ObjectMapper objectMapper;
    private final CoreLlmProperties coreLlmProperties;

    public LlmCoreIntentInterpreter(
            CodexAppServerClient codexAppServerClient,
            CodexAppServerProperties codexAppServerProperties,
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            ObjectMapper objectMapper,
            CoreLlmProperties coreLlmProperties
    ) {
        this.codexAppServerClient = codexAppServerClient;
        this.codexAppServerProperties = codexAppServerProperties;
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.objectMapper = objectMapper;
        this.coreLlmProperties = coreLlmProperties;
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
        String intent = text(node, "intent");
        String domain = text(node, "domain");
        String capability = text(node, "capability");
        if (!"DEVELOPMENT".equalsIgnoreCase(domain) || capability == null || "unknown".equalsIgnoreCase(capability)) {
            throw new CoreUnknownIntentException("Core LLM could not determine a supported development capability");
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        JsonNode parametersNode = node.path("parameters");
        if (parametersNode.isObject()) {
            putLong(parameters, "projectId", parametersNode.get("projectId"));
            putLong(parameters, "workSessionId", parametersNode.get("workSessionId"));
            putText(parameters, "title", parametersNode.get("title"));
        }

        if ("continue_work_session".equals(capability)) {
            parameters.put("message", request.input().trim());
            if (!parameters.containsKey("workSessionId")) {
                throw new CoreUnknownIntentException(
                        "Core LLM could not determine the target WorkSession for the current request");
            }
        } else if ("create_work_session".equals(capability) && !parameters.containsKey("title")) {
            parameters.put("title", request.input().trim());
        }

        if ("create_work_session".equals(capability) && !parameters.containsKey("projectId")) {
            throw new CoreUnknownIntentException(
                    "Core LLM could not determine the target project for the current request");
        }

        String interpretationDetail = completeImplicitResolution(request, capability, parameters);
        BigDecimal confidence = node.hasNonNull("confidence")
                ? node.get("confidence").decimalValue()
                : BigDecimal.valueOf(0.50);

        return new CoreInterpretationResult(
                new CoreIntentProposal(
                        intent == null ? capability.toUpperCase() : intent,
                        CoreDomain.DEVELOPMENT,
                        capability,
                        parameters,
                        confidence),
                CoreInterpreterSource.LLM,
                interpretationDetail);
    }

    private String completeImplicitResolution(
            CreateCoreCommandRequest request,
            String capability,
            Map<String, Object> parameters
    ) {
        if ("continue_work_session".equals(capability) && !parameters.containsKey("workSessionId")) {
            WorkSessionEntity resolvedSession = resolveImplicitSession(request.input().trim(), parameters);
            if (resolvedSession != null) {
                parameters.put("workSessionId", resolvedSession.getId());
                parameters.putIfAbsent("projectId", resolvedSession.getProject().getId());
                return "llm_interpretation_with_implicit_session_resolution";
            }
        }

        if ("create_work_session".equals(capability) && !parameters.containsKey("projectId")) {
            ProjectEntity resolvedProject = resolveImplicitProject(request.input().trim());
            if (resolvedProject != null) {
                parameters.put("projectId", resolvedProject.getId());
                return "llm_interpretation_with_implicit_project_resolution";
            }
        }

        return "llm_structured_classification";
    }

    private WorkSessionEntity resolveImplicitSession(String input, Map<String, Object> parameters) {
        Long projectId = parameters.get("projectId") instanceof Number number ? number.longValue() : null;
        List<WorkSessionEntity> sessions = projectId == null
                ? workSessionRepository.findByStatusInOrderByLastActivityAtDesc(ACTIVE_SESSION_STATUSES)
                : workSessionRepository.findByProjectIdOrderByLastActivityAtDesc(projectId).stream()
                        .filter(session -> ACTIVE_SESSION_STATUSES.contains(session.getStatus()))
                        .toList();
        if (sessions.size() == 1) {
            return sessions.getFirst();
        }

        List<WorkSessionEntity> byProjectName = sessions.stream()
                .filter(session -> input.toLowerCase().contains(session.getProject().getName().toLowerCase()))
                .toList();
        if (byProjectName.size() == 1) {
            return byProjectName.getFirst();
        }

        List<WorkSessionEntity> byTitle = sessions.stream()
                .filter(session -> input.toLowerCase().contains(session.getTitle().toLowerCase()))
                .toList();
        if (byTitle.size() == 1) {
            return byTitle.getFirst();
        }

        return null;
    }

    private ProjectEntity resolveImplicitProject(String input) {
        List<ProjectEntity> projects = projectRepository.findAll();
        if (projects.size() == 1) {
            return projects.getFirst();
        }

        List<ProjectEntity> matchingByName = projects.stream()
                .filter(project -> input.toLowerCase().contains(project.getName().toLowerCase()))
                .toList();
        if (matchingByName.size() == 1) {
            return matchingByName.getFirst();
        }

        List<ProjectEntity> matchingByDescription = projects.stream()
                .filter(project -> project.getDescription() != null
                        && !project.getDescription().isBlank()
                        && input.toLowerCase().contains(project.getDescription().toLowerCase()))
                .toList();
        if (matchingByDescription.size() == 1) {
            return matchingByDescription.getFirst();
        }

        return null;
    }

    private void putLong(Map<String, Object> parameters, String key, JsonNode value) {
        if (value != null && value.isNumber()) {
            parameters.put(key, value.longValue());
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
