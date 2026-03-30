package com.atenea.service.worksession;

import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.api.worksession.MarkPriceEstimateBilledRequest;
import com.atenea.api.worksession.SessionDeliverableResponse;
import com.atenea.api.worksession.SessionDeliverableHistoryResponse;
import com.atenea.api.worksession.SessionDeliverableSummaryResponse;
import com.atenea.api.worksession.SessionDeliverablesViewResponse;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionDeliverableStatus;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.mobilepush.MobilePushDispatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionDeliverableService {

    private final SessionDeliverableRepository sessionDeliverableRepository;
    private final WorkSessionRepository workSessionRepository;
    private final ObjectMapper objectMapper;
    private final MobilePushDispatchService mobilePushDispatchService;

    public SessionDeliverableService(
            SessionDeliverableRepository sessionDeliverableRepository,
            WorkSessionRepository workSessionRepository,
            ObjectMapper objectMapper,
            MobilePushDispatchService mobilePushDispatchService
    ) {
        this.sessionDeliverableRepository = sessionDeliverableRepository;
        this.workSessionRepository = workSessionRepository;
        this.objectMapper = objectMapper;
        this.mobilePushDispatchService = mobilePushDispatchService;
    }

    @Transactional(readOnly = true)
    public SessionDeliverablesViewResponse getDeliverablesView(Long sessionId) {
        ensureSessionExists(sessionId);
        List<SessionDeliverableEntity> deliverables = sessionDeliverableRepository.findBySessionIdOrderByTypeAscVersionDesc(sessionId);
        return toView(sessionId, deliverables);
    }

    @Transactional(readOnly = true)
    public SessionDeliverablesViewResponse getApprovedDeliverablesView(Long sessionId) {
        ensureSessionExists(sessionId);
        List<SessionDeliverableEntity> deliverables = sessionDeliverableRepository
                .findBySessionIdAndApprovedTrueOrderByTypeAscVersionDesc(sessionId);
        return toView(sessionId, deliverables);
    }

    @Transactional(readOnly = true)
    public SessionDeliverableHistoryResponse getDeliverableHistory(Long sessionId, SessionDeliverableType type) {
        ensureSessionExists(sessionId);
        List<SessionDeliverableEntity> deliverables = sessionDeliverableRepository
                .findBySessionIdAndTypeOrderByVersionDesc(sessionId, type);
        Long latestGeneratedDeliverableId = deliverables.isEmpty() ? null : deliverables.get(0).getId();
        Long latestApprovedDeliverableId = deliverables.stream()
                .filter(SessionDeliverableEntity::isApproved)
                .map(SessionDeliverableEntity::getId)
                .findFirst()
                .orElse(null);
        List<SessionDeliverableSummaryResponse> versions = deliverables.stream()
                .map(deliverable -> toSummary(deliverable, latestApprovedDeliverableId))
                .toList();
        return new SessionDeliverableHistoryResponse(
                sessionId,
                type,
                latestGeneratedDeliverableId,
                latestApprovedDeliverableId,
                versions
        );
    }

    @Transactional(readOnly = true, noRollbackFor = ApprovedPriceEstimateNotFoundException.class)
    public ApprovedPriceEstimateSummaryResponse getApprovedPriceEstimateSummary(Long sessionId) {
        ensureSessionExists(sessionId);
        SessionDeliverableEntity deliverable = sessionDeliverableRepository
                .findBySessionIdAndApprovedTrueOrderByTypeAscVersionDesc(sessionId)
                .stream()
                .filter(candidate -> candidate.getType() == SessionDeliverableType.PRICE_ESTIMATE)
                .findFirst()
                .orElseThrow(() -> new ApprovedPriceEstimateNotFoundException(sessionId));
        JsonNode content = parseRequiredContentJson(deliverable);
        return new ApprovedPriceEstimateSummaryResponse(
                sessionId,
                deliverable.getId(),
                deliverable.getVersion(),
                deliverable.getTitle(),
                requireText(content, "currency"),
                requireNumber(content, "baseHourlyRate"),
                requireNumber(content, "equivalentHours"),
                requireNumber(content, "minimumPrice"),
                requireNumber(content, "recommendedPrice"),
                requireNumber(content, "maximumPrice"),
                requireText(content, "commercialPositioning"),
                requireText(content, "riskLevel"),
                requireText(content, "confidence"),
                requireStringList(content, "assumptions"),
                requireStringList(content, "exclusions"),
                deliverable.getBillingStatus(),
                deliverable.getBillingReference(),
                deliverable.getBilledAt(),
                deliverable.getApprovedAt(),
                deliverable.getUpdatedAt()
        );
    }

    @Transactional
    public SessionDeliverableResponse approveDeliverable(Long sessionId, Long deliverableId) {
        ensureSessionExists(sessionId);
        SessionDeliverableEntity deliverable = sessionDeliverableRepository.findByIdAndSessionId(deliverableId, sessionId)
                .orElseThrow(() -> new SessionDeliverableNotFoundException(sessionId, deliverableId));

        if (deliverable.getStatus() != SessionDeliverableStatus.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "SessionDeliverable '%s' cannot be approved because it is not SUCCEEDED".formatted(deliverableId));
        }

        supersedeOtherApprovedVersions(sessionId, deliverable);

        boolean billingReadyTransition = false;
        if (!deliverable.isApproved()) {
            Instant now = Instant.now();
            deliverable.setApproved(true);
            deliverable.setApprovedAt(now);
            if (deliverable.getType() == SessionDeliverableType.PRICE_ESTIMATE
                    && deliverable.getBillingStatus() == null) {
                deliverable.setBillingStatus(SessionDeliverableBillingStatus.READY);
                billingReadyTransition = true;
            }
            deliverable.setUpdatedAt(now);
            deliverable = sessionDeliverableRepository.save(deliverable);
        }

        if (billingReadyTransition) {
            mobilePushDispatchService.notifyBillingReady(deliverable);
        }

        return toResponse(deliverable);
    }

    @Transactional
    public SessionDeliverableResponse markPriceEstimateBilled(
            Long sessionId,
            Long deliverableId,
            MarkPriceEstimateBilledRequest request
    ) {
        ensureSessionExists(sessionId);
        SessionDeliverableEntity deliverable = sessionDeliverableRepository.findByIdAndSessionId(deliverableId, sessionId)
                .orElseThrow(() -> new SessionDeliverableNotFoundException(sessionId, deliverableId));

        if (deliverable.getType() != SessionDeliverableType.PRICE_ESTIMATE) {
            throw new IllegalArgumentException(
                    "SessionDeliverable '%s' cannot be billed because it is not PRICE_ESTIMATE".formatted(deliverableId));
        }
        if (!deliverable.isApproved()) {
            throw new IllegalArgumentException(
                    "SessionDeliverable '%s' cannot be billed because it is not approved".formatted(deliverableId));
        }
        if (deliverable.getBillingStatus() == SessionDeliverableBillingStatus.BILLED) {
            throw new IllegalArgumentException(
                    "SessionDeliverable '%s' is already marked as billed".formatted(deliverableId));
        }

        Instant now = Instant.now();
        deliverable.setBillingStatus(SessionDeliverableBillingStatus.BILLED);
        deliverable.setBillingReference(normalizeRequiredText(request.billingReference()));
        deliverable.setBilledAt(now);
        deliverable.setUpdatedAt(now);
        return toResponse(sessionDeliverableRepository.save(deliverable));
    }

    private void supersedeOtherApprovedVersions(Long sessionId, SessionDeliverableEntity approvedDeliverable) {
        List<SessionDeliverableEntity> existing = sessionDeliverableRepository.findBySessionIdAndTypeOrderByVersionDesc(
                sessionId,
                approvedDeliverable.getType());
        Instant now = Instant.now();
        for (SessionDeliverableEntity candidate : existing) {
            if (candidate.getId().equals(approvedDeliverable.getId())) {
                continue;
            }
            if (!candidate.isApproved()) {
                continue;
            }
            candidate.setApproved(false);
            candidate.setApprovedAt(null);
            if (candidate.getStatus() == SessionDeliverableStatus.SUCCEEDED) {
                candidate.setStatus(SessionDeliverableStatus.SUPERSEDED);
            }
            candidate.setUpdatedAt(now);
            sessionDeliverableRepository.save(candidate);
        }
    }

    private SessionDeliverablesViewResponse toView(Long sessionId, List<SessionDeliverableEntity> deliverables) {

        Map<SessionDeliverableType, SessionDeliverableEntity> latestByType = new LinkedHashMap<>();
        Map<SessionDeliverableType, Long> latestApprovedByType = new LinkedHashMap<>();
        for (SessionDeliverableEntity deliverable : deliverables) {
            latestByType.putIfAbsent(deliverable.getType(), deliverable);
            if (deliverable.isApproved()) {
                latestApprovedByType.putIfAbsent(deliverable.getType(), deliverable.getId());
            }
        }

        List<SessionDeliverableSummaryResponse> summaries = new ArrayList<>();
        Instant lastGeneratedAt = null;
        for (SessionDeliverableEntity deliverable : latestByType.values()) {
            summaries.add(toSummary(deliverable, latestApprovedByType.get(deliverable.getType())));
            if (lastGeneratedAt == null || deliverable.getUpdatedAt().isAfter(lastGeneratedAt)) {
                lastGeneratedAt = deliverable.getUpdatedAt();
            }
        }

        EnumSet<SessionDeliverableType> requiredTypes = EnumSet.allOf(SessionDeliverableType.class);
        boolean allCoreDeliverablesPresent = latestByType.keySet().containsAll(requiredTypes);
        boolean allCoreDeliverablesApproved = allCoreDeliverablesPresent
                && latestByType.values().stream()
                .allMatch(deliverable -> deliverable.isApproved()
                        && deliverable.getStatus() == SessionDeliverableStatus.SUCCEEDED);

        return new SessionDeliverablesViewResponse(
                sessionId,
                summaries,
                allCoreDeliverablesPresent,
                allCoreDeliverablesApproved,
                lastGeneratedAt
        );
    }

    @Transactional(readOnly = true)
    public SessionDeliverableResponse getDeliverable(Long sessionId, Long deliverableId) {
        ensureSessionExists(sessionId);
        SessionDeliverableEntity deliverable = sessionDeliverableRepository.findByIdAndSessionId(deliverableId, sessionId)
                .orElseThrow(() -> new SessionDeliverableNotFoundException(sessionId, deliverableId));
        return toResponse(deliverable);
    }

    private void ensureSessionExists(Long sessionId) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }
    }

    private SessionDeliverableSummaryResponse toSummary(SessionDeliverableEntity deliverable, Long latestApprovedDeliverableId) {
        return new SessionDeliverableSummaryResponse(
                deliverable.getId(),
                deliverable.getType(),
                deliverable.getStatus(),
                deliverable.getVersion(),
                deliverable.getTitle(),
                deliverable.isApproved(),
                deliverable.getApprovedAt(),
                deliverable.getUpdatedAt(),
                preview(deliverable.getContentMarkdown(), deliverable.getErrorMessage()),
                latestApprovedDeliverableId
        );
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
                deliverable.getBillingStatus(),
                deliverable.getBillingReference(),
                deliverable.getBilledAt(),
                deliverable.getCreatedAt(),
                deliverable.getUpdatedAt()
        );
    }

    private String normalizeRequiredText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("billingReference must not be blank");
        }
        return value.trim();
    }

    private String preview(String markdown, String errorMessage) {
        String source = markdown != null && !markdown.isBlank() ? markdown : errorMessage;
        if (source == null || source.isBlank()) {
            return null;
        }

        String normalized = source.replace('\n', ' ').trim().replaceAll("\\s+", " ");
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }

    private JsonNode parseRequiredContentJson(SessionDeliverableEntity deliverable) {
        if (deliverable.getContentJson() == null || deliverable.getContentJson().isBlank()) {
            throw new IllegalStateException("Approved PRICE_ESTIMATE is missing structured JSON content");
        }
        try {
            return objectMapper.readTree(deliverable.getContentJson());
        } catch (Exception exception) {
            throw new IllegalStateException("Approved PRICE_ESTIMATE contains invalid structured JSON", exception);
        }
    }

    private String requireText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new IllegalStateException("Approved PRICE_ESTIMATE JSON is missing field '" + fieldName + "'");
        }
        return field.asText();
    }

    private double requireNumber(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isNumber()) {
            throw new IllegalStateException("Approved PRICE_ESTIMATE JSON is missing numeric field '" + fieldName + "'");
        }
        return field.asDouble();
    }

    private List<String> requireStringList(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalStateException("Approved PRICE_ESTIMATE JSON is missing array field '" + fieldName + "'");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : field) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalStateException("Approved PRICE_ESTIMATE JSON field '" + fieldName + "' must contain strings");
            }
            values.add(item.asText());
        }
        return values;
    }
}
