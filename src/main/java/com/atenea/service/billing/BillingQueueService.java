package com.atenea.service.billing;

import com.atenea.api.billing.BillingAmountSummaryResponse;
import com.atenea.api.billing.BillingQueueItemResponse;
import com.atenea.api.billing.BillingQueueResponse;
import com.atenea.api.billing.BillingQueueSummaryResponse;
import com.atenea.persistence.worksession.SessionDeliverableBillingStatus;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingQueueService {

    private final SessionDeliverableRepository sessionDeliverableRepository;
    private final ObjectMapper objectMapper;

    public BillingQueueService(
            SessionDeliverableRepository sessionDeliverableRepository,
            ObjectMapper objectMapper
    ) {
        this.sessionDeliverableRepository = sessionDeliverableRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public BillingQueueResponse getQueue(
            SessionDeliverableBillingStatus billingStatus,
            Long projectId,
            Long sessionId,
            String query
    ) {
        return new BillingQueueResponse(filterAndMapDeliverables(billingStatus, projectId, sessionId, query));
    }

    @Transactional(readOnly = true)
    public BillingQueueSummaryResponse getQueueSummary(
            SessionDeliverableBillingStatus billingStatus,
            Long projectId,
            Long sessionId,
            String query
    ) {
        List<BillingQueueItemResponse> items = filterAndMapDeliverables(billingStatus, projectId, sessionId, query);
        List<BillingQueueItemResponse> readyItems = items.stream()
                .filter(item -> item.billingStatus() == SessionDeliverableBillingStatus.READY)
                .toList();
        List<BillingQueueItemResponse> billedItems = items.stream()
                .filter(item -> item.billingStatus() == SessionDeliverableBillingStatus.BILLED)
                .toList();
        return new BillingQueueSummaryResponse(
                readyItems.size(),
                billedItems.size(),
                sumAmountsByCurrency(readyItems),
                sumAmountsByCurrency(billedItems)
        );
    }

    private List<BillingQueueItemResponse> filterAndMapDeliverables(
            SessionDeliverableBillingStatus billingStatus,
            Long projectId,
            Long sessionId,
            String query
    ) {
        String normalizedQuery = normalizeNullableText(query);
        return sessionDeliverableRepository.findByTypeAndApprovedTrueOrderByApprovedAtDesc(
                        SessionDeliverableType.PRICE_ESTIMATE)
                .stream()
                .filter(deliverable -> matchesBillingStatus(deliverable, billingStatus))
                .filter(deliverable -> projectId == null || deliverable.getSession().getProject().getId().equals(projectId))
                .filter(deliverable -> sessionId == null || deliverable.getSession().getId().equals(sessionId))
                .filter(deliverable -> matchesQuery(deliverable, normalizedQuery))
                .map(this::toQueueItem)
                .sorted(queueOrdering())
                .toList();
    }

    private Comparator<BillingQueueItemResponse> queueOrdering() {
        return Comparator.comparing(BillingQueueItemResponse::billingStatus, this::compareBillingStatus)
                .thenComparing(BillingQueueItemResponse::approvedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BillingQueueItemResponse::sessionId, Comparator.reverseOrder());
    }

    private int compareBillingStatus(
            SessionDeliverableBillingStatus left,
            SessionDeliverableBillingStatus right
    ) {
        return billingRank(left) - billingRank(right);
    }

    private int billingRank(SessionDeliverableBillingStatus status) {
        if (status == SessionDeliverableBillingStatus.READY) {
            return 0;
        }
        if (status == SessionDeliverableBillingStatus.BILLED) {
            return 1;
        }
        return 2;
    }

    private boolean matchesBillingStatus(
            SessionDeliverableEntity deliverable,
            SessionDeliverableBillingStatus billingStatus
    ) {
        if (billingStatus == null) {
            return true;
        }
        return billingStatus == resolveBillingStatus(deliverable);
    }

    private boolean matchesQuery(SessionDeliverableEntity deliverable, String query) {
        if (query == null) {
            return true;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return contains(deliverable.getSession().getProject().getName(), normalized)
                || contains(deliverable.getSession().getTitle(), normalized)
                || contains(deliverable.getBillingReference(), normalized);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private BillingQueueItemResponse toQueueItem(SessionDeliverableEntity deliverable) {
        JsonNode content = parseRequiredContentJson(deliverable);
        return new BillingQueueItemResponse(
                deliverable.getSession().getProject().getId(),
                deliverable.getSession().getProject().getName(),
                deliverable.getSession().getId(),
                deliverable.getSession().getTitle(),
                deliverable.getId(),
                deliverable.getVersion(),
                resolveBillingStatus(deliverable),
                deliverable.getBillingReference(),
                deliverable.getBilledAt(),
                requireText(content, "currency"),
                requireNumber(content, "recommendedPrice"),
                requireNumber(content, "minimumPrice"),
                requireNumber(content, "maximumPrice"),
                deliverable.getApprovedAt(),
                deliverable.getSession().getPublishedAt(),
                deliverable.getSession().getPullRequestUrl(),
                deliverable.getSession().getPullRequestStatus()
        );
    }

    private SessionDeliverableBillingStatus resolveBillingStatus(SessionDeliverableEntity deliverable) {
        return deliverable.getBillingStatus() == null ? SessionDeliverableBillingStatus.READY : deliverable.getBillingStatus();
    }

    private List<BillingAmountSummaryResponse> sumAmountsByCurrency(List<BillingQueueItemResponse> items) {
        Map<String, Double> totals = new LinkedHashMap<>();
        for (BillingQueueItemResponse item : items) {
            totals.merge(item.currency(), item.recommendedPrice(), Double::sum);
        }
        List<BillingAmountSummaryResponse> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : totals.entrySet()) {
            result.add(new BillingAmountSummaryResponse(entry.getKey(), entry.getValue()));
        }
        return result;
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

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
