package com.atenea.service.project;

import com.atenea.api.project.ProjectApprovedPriceEstimatesResponse;
import com.atenea.api.worksession.ApprovedPriceEstimateSummaryResponse;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionProjectNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectApprovedPriceEstimateService {

    private final ProjectRepository projectRepository;
    private final WorkSessionRepository workSessionRepository;
    private final SessionDeliverableRepository sessionDeliverableRepository;
    private final ObjectMapper objectMapper;

    public ProjectApprovedPriceEstimateService(
            ProjectRepository projectRepository,
            WorkSessionRepository workSessionRepository,
            SessionDeliverableRepository sessionDeliverableRepository,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.workSessionRepository = workSessionRepository;
        this.sessionDeliverableRepository = sessionDeliverableRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ProjectApprovedPriceEstimatesResponse getApprovedPriceEstimates(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new WorkSessionProjectNotFoundException(projectId);
        }

        List<ApprovedPriceEstimateSummaryResponse> approvedPriceEstimates = new ArrayList<>();
        for (WorkSessionEntity session : workSessionRepository.findByProjectIdOrderByLastActivityAtDesc(projectId)) {
            sessionDeliverableRepository.findBySessionIdAndApprovedTrueOrderByTypeAscVersionDesc(session.getId())
                    .stream()
                    .filter(deliverable -> deliverable.getType() == SessionDeliverableType.PRICE_ESTIMATE)
                    .findFirst()
                    .ifPresent(deliverable -> approvedPriceEstimates.add(toSummary(deliverable)));
        }

        return new ProjectApprovedPriceEstimatesResponse(projectId, approvedPriceEstimates);
    }

    private ApprovedPriceEstimateSummaryResponse toSummary(SessionDeliverableEntity deliverable) {
        JsonNode content;
        try {
            content = objectMapper.readTree(deliverable.getContentJson());
        } catch (Exception exception) {
            throw new IllegalStateException("Approved PRICE_ESTIMATE contains invalid structured JSON", exception);
        }

        return new ApprovedPriceEstimateSummaryResponse(
                deliverable.getSession().getId(),
                deliverable.getId(),
                deliverable.getVersion(),
                deliverable.getTitle(),
                content.get("currency").asText(),
                content.get("baseHourlyRate").asDouble(),
                content.get("equivalentHours").asDouble(),
                content.get("minimumPrice").asDouble(),
                content.get("recommendedPrice").asDouble(),
                content.get("maximumPrice").asDouble(),
                content.get("commercialPositioning").asText(),
                content.get("riskLevel").asText(),
                content.get("confidence").asText(),
                toStringList(content.get("assumptions")),
                toStringList(content.get("exclusions")),
                deliverable.getBillingStatus(),
                deliverable.getBillingReference(),
                deliverable.getBilledAt(),
                deliverable.getApprovedAt(),
                deliverable.getUpdatedAt()
        );
    }

    private List<String> toStringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                values.add(item.asText());
            }
        }
        return values;
    }
}
