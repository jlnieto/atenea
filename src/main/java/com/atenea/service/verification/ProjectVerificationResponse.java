package com.atenea.service.verification;

import com.atenea.persistence.verification.ProjectVerificationStatus;
import java.time.Instant;
import java.util.List;

public record ProjectVerificationResponse(
        Long id,
        Long projectId,
        Long workSessionId,
        ProjectVerificationStatus status,
        String runtimeContractPath,
        String runtimeProfile,
        String baseUrl,
        String decisionBrief,
        String technicalSummary,
        String blockerType,
        String blockerSummary,
        String recommendedAction,
        List<ProjectVerificationTestResult> tests,
        List<String> artifacts,
        Instant startedAt,
        Instant finishedAt
) {
}
