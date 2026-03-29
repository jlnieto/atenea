package com.atenea.service.worksession;

import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionDeliverableGenerationService {

    private static final String WORK_TICKET_PROMPT_VERSION = "session-deliverables-work-ticket-v1";
    private static final String WORK_BREAKDOWN_PROMPT_VERSION = "session-deliverables-work-breakdown-v1";
    private static final String PRICE_ESTIMATE_PROMPT_VERSION = "session-deliverables-price-estimate-v2";
    private static final double PRICE_ESTIMATE_BASE_HOURLY_RATE_EUR = 43.0d;

    private final SessionDeliverableRepository sessionDeliverableRepository;
    private final WorkSessionRepository workSessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final AgentRunRepository agentRunRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final ObjectMapper objectMapper;
    private final SessionDeliverableCodexOrchestrator sessionDeliverableCodexOrchestrator;

    public SessionDeliverableGenerationService(
            SessionDeliverableRepository sessionDeliverableRepository,
            WorkSessionRepository workSessionRepository,
            SessionTurnRepository sessionTurnRepository,
            AgentRunRepository agentRunRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            ObjectMapper objectMapper,
            SessionDeliverableCodexOrchestrator sessionDeliverableCodexOrchestrator
    ) {
        this.sessionDeliverableRepository = sessionDeliverableRepository;
        this.workSessionRepository = workSessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.agentRunRepository = agentRunRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.objectMapper = objectMapper;
        this.sessionDeliverableCodexOrchestrator = sessionDeliverableCodexOrchestrator;
    }

    @Transactional
    public SessionDeliverableResponse generateDeliverable(Long sessionId, SessionDeliverableType type) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());

        String snapshotJson = buildSnapshotJson(session, type);
        supersedeOlderGeneratedVersions(sessionId, type);
        int nextVersion = sessionDeliverableRepository.findFirstBySessionIdAndTypeOrderByVersionDesc(sessionId, type)
                .map(deliverable -> deliverable.getVersion() + 1)
                .orElse(1);

        Instant now = Instant.now();
        SessionDeliverableEntity deliverable = new SessionDeliverableEntity();
        deliverable.setSession(session);
        deliverable.setType(type);
        deliverable.setStatus(SessionDeliverableStatus.RUNNING);
        deliverable.setVersion(nextVersion);
        deliverable.setTitle(defaultTitle(type, nextVersion));
        deliverable.setContentMarkdown(null);
        deliverable.setContentJson(null);
        deliverable.setInputSnapshotJson(snapshotJson);
        deliverable.setGenerationNotes("Generated from persisted session evidence snapshot");
        deliverable.setErrorMessage(null);
        deliverable.setModel("codex-app-server");
        deliverable.setPromptVersion(promptVersion(type));
        deliverable.setApproved(false);
        deliverable.setApprovedAt(null);
        deliverable.setCreatedAt(now);
        deliverable.setUpdatedAt(now);
        sessionDeliverableRepository.save(deliverable);

        try {
            GeneratedDeliverableContent generatedContent = generateContent(type, repoPath, snapshotJson);
            Instant completedAt = Instant.now();
            deliverable.setStatus(SessionDeliverableStatus.SUCCEEDED);
            deliverable.setContentMarkdown(generatedContent.markdown());
            deliverable.setContentJson(generatedContent.contentJson());
            deliverable.setErrorMessage(null);
            deliverable.setUpdatedAt(completedAt);
            session.setLastActivityAt(completedAt);
            session.setUpdatedAt(completedAt);
            workSessionRepository.save(session);
            return toResponse(sessionDeliverableRepository.save(deliverable));
        } catch (Exception exception) {
            deliverable.setStatus(SessionDeliverableStatus.FAILED);
            deliverable.setErrorMessage(firstNonBlank(exception.getMessage(), "Deliverable generation failed"));
            deliverable.setUpdatedAt(Instant.now());
            return toResponse(sessionDeliverableRepository.save(deliverable));
        }
    }

    private void supersedeOlderGeneratedVersions(Long sessionId, SessionDeliverableType type) {
        List<SessionDeliverableEntity> existing = sessionDeliverableRepository.findBySessionIdAndTypeOrderByVersionDesc(sessionId, type);
        Instant now = Instant.now();
        for (SessionDeliverableEntity deliverable : existing) {
            if (deliverable.isApproved()) {
                continue;
            }
            if (deliverable.getStatus() == SessionDeliverableStatus.SUPERSEDED) {
                continue;
            }
            deliverable.setStatus(SessionDeliverableStatus.SUPERSEDED);
            deliverable.setUpdatedAt(now);
            sessionDeliverableRepository.save(deliverable);
        }
    }

    private GeneratedDeliverableContent generateContent(SessionDeliverableType type, String repoPath, String snapshotJson) throws Exception {
        return switch (type) {
            case WORK_TICKET -> new GeneratedDeliverableContent(
                    sessionDeliverableCodexOrchestrator.generateWorkTicket(repoPath, snapshotJson),
                    null);
            case WORK_BREAKDOWN -> new GeneratedDeliverableContent(
                    sessionDeliverableCodexOrchestrator.generateWorkBreakdown(repoPath, snapshotJson),
                    null);
            case PRICE_ESTIMATE -> toValidatedPriceEstimate(
                    sessionDeliverableCodexOrchestrator.generatePriceEstimate(repoPath, snapshotJson));
        };
    }

    private GeneratedDeliverableContent toValidatedPriceEstimate(
            SessionDeliverableCodexOrchestrator.PriceEstimateGenerationResult result
    ) {
        JsonNode node = parseRequiredJson(result.contentJson());
        validatePriceEstimateJson(node);
        try {
            return new GeneratedDeliverableContent(result.markdown(), objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not normalize PRICE_ESTIMATE structured output", exception);
        }
    }

    private JsonNode parseRequiredJson(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new IllegalStateException("PRICE_ESTIMATE did not include structured JSON output");
        }
        try {
            return objectMapper.readTree(contentJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("PRICE_ESTIMATE returned invalid JSON output", exception);
        }
    }

    private void validatePriceEstimateJson(JsonNode node) {
        requireText(node, "currency");
        requireNumber(node, "baseHourlyRate");
        requireNumber(node, "equivalentHours");
        double minimumPrice = requireNumber(node, "minimumPrice");
        double recommendedPrice = requireNumber(node, "recommendedPrice");
        double maximumPrice = requireNumber(node, "maximumPrice");
        requireText(node, "commercialPositioning");
        requireText(node, "riskLevel");
        requireText(node, "confidence");
        requireStringArray(node, "assumptions");
        requireStringArray(node, "exclusions");
        if (minimumPrice > recommendedPrice || recommendedPrice > maximumPrice) {
            throw new IllegalStateException("PRICE_ESTIMATE recommendedPrice must be between minimumPrice and maximumPrice");
        }
    }

    private String requireText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new IllegalStateException("PRICE_ESTIMATE JSON is missing required text field '" + fieldName + "'");
        }
        return field.asText();
    }

    private double requireNumber(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isNumber()) {
            throw new IllegalStateException("PRICE_ESTIMATE JSON is missing required numeric field '" + fieldName + "'");
        }
        return field.asDouble();
    }

    private void requireStringArray(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalStateException("PRICE_ESTIMATE JSON is missing required array field '" + fieldName + "'");
        }
        for (JsonNode item : field) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException(
                        "PRICE_ESTIMATE JSON field '" + fieldName + "' must contain non-blank strings");
            }
        }
    }

    private String promptVersion(SessionDeliverableType type) {
        return switch (type) {
            case WORK_TICKET -> WORK_TICKET_PROMPT_VERSION;
            case WORK_BREAKDOWN -> WORK_BREAKDOWN_PROMPT_VERSION;
            case PRICE_ESTIMATE -> PRICE_ESTIMATE_PROMPT_VERSION;
        };
    }

    private String buildSnapshotJson(WorkSessionEntity session, SessionDeliverableType type) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("generatedAt", Instant.now().toString());
        snapshot.put("deliverableType", type.name());
        snapshot.put("session", buildSessionSnapshot(session));
        snapshot.put("turns", buildTurnSnapshots(session.getId()));
        snapshot.put("runs", buildRunSnapshots(session.getId()));
        if (type == SessionDeliverableType.PRICE_ESTIMATE) {
            snapshot.put("pricingPolicy", buildPricingPolicySnapshot());
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize session deliverable snapshot", exception);
        }
    }

    private Map<String, Object> buildSessionSnapshot(WorkSessionEntity session) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", session.getId());
        snapshot.put("projectId", session.getProject().getId());
        snapshot.put("projectName", session.getProject().getName());
        snapshot.put("title", session.getTitle());
        snapshot.put("status", session.getStatus().name());
        snapshot.put("baseBranch", session.getBaseBranch());
        snapshot.put("workspaceBranch", session.getWorkspaceBranch());
        snapshot.put("externalThreadId", session.getExternalThreadId());
        snapshot.put("pullRequestUrl", session.getPullRequestUrl());
        snapshot.put("pullRequestStatus", session.getPullRequestStatus().name());
        snapshot.put("finalCommitSha", session.getFinalCommitSha());
        snapshot.put("openedAt", session.getOpenedAt());
        snapshot.put("lastActivityAt", session.getLastActivityAt());
        snapshot.put("publishedAt", session.getPublishedAt());
        snapshot.put("closedAt", session.getClosedAt());
        return snapshot;
    }

    private List<Map<String, Object>> buildTurnSnapshots(Long sessionId) {
        return sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .filter(turn -> !turn.isInternal())
                .map(this::toTurnSnapshot)
                .toList();
    }

    private Map<String, Object> toTurnSnapshot(SessionTurnEntity turn) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", turn.getId());
        snapshot.put("actor", turn.getActor().name());
        snapshot.put("messageText", turn.getMessageText());
        snapshot.put("createdAt", turn.getCreatedAt());
        return snapshot;
    }

    private List<Map<String, Object>> buildRunSnapshots(Long sessionId) {
        return agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toRunSnapshot)
                .toList();
    }

    private Map<String, Object> toRunSnapshot(AgentRunEntity run) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", run.getId());
        snapshot.put("status", run.getStatus().name());
        snapshot.put("originTurnId", run.getOriginTurn() == null ? null : run.getOriginTurn().getId());
        snapshot.put("resultTurnId", run.getResultTurn() == null ? null : run.getResultTurn().getId());
        snapshot.put("externalTurnId", run.getExternalTurnId());
        snapshot.put("startedAt", run.getStartedAt());
        snapshot.put("finishedAt", run.getFinishedAt());
        snapshot.put("outputSummary", run.getOutputSummary());
        snapshot.put("errorSummary", run.getErrorSummary());
        snapshot.put("createdAt", run.getCreatedAt());
        return snapshot;
    }

    private Map<String, Object> buildPricingPolicySnapshot() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("market", "ES");
        policy.put("currency", "EUR");
        policy.put("baseHourlyRate", PRICE_ESTIMATE_BASE_HOURLY_RATE_EUR);
        policy.put("commercialPositioning", "competitive");
        policy.put("riskLevel", "low");
        policy.put("vatIncluded", false);
        policy.put("minimumBillableBase", null);
        policy.put("roundingPolicy", "none");
        policy.put("orchestrationSurcharge", false);
        policy.put("includeEquivalentHoursInternally", true);
        policy.put("outputAudience", "internal");
        policy.put("justificationDepth", "medium");
        policy.put("mustIncludeAssumptions", true);
        policy.put("mustIncludeExclusions", true);
        policy.put("mustProvideRangeAndRecommendation", true);
        policy.put("manualApprovalRequired", true);
        return policy;
    }

    private String defaultTitle(SessionDeliverableType type, int version) {
        return switch (type) {
            case WORK_TICKET -> "Work ticket v" + version;
            case WORK_BREAKDOWN -> "Work breakdown v" + version;
            case PRICE_ESTIMATE -> "Price estimate v" + version;
        };
    }

    private SessionDeliverableResponse toResponse(SessionDeliverableEntity deliverable) {
        return new SessionDeliverableResponse(
                deliverable.getId(),
                deliverable.getSession().getId(),
                deliverable.getType(),
                deliverable.getStatus(),
                deliverable.getVersion(),
                deliverable.getTitle(),
                deliverable.getContentMarkdown(),
                deliverable.getContentJson(),
                deliverable.getInputSnapshotJson(),
                deliverable.getGenerationNotes(),
                deliverable.getErrorMessage(),
                deliverable.getModel(),
                deliverable.getPromptVersion(),
                deliverable.isApproved(),
                deliverable.getApprovedAt(),
                deliverable.getCreatedAt(),
                deliverable.getUpdatedAt()
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record GeneratedDeliverableContent(
            String markdown,
            String contentJson
    ) {
    }
}
