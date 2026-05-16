package com.atenea.api.operations;

import java.util.List;
import java.util.Map;

public record OperationsExecutionReportResponse(
        String action,
        String host,
        String status,
        String summary,
        List<OperationsExecutionStepResponse> steps,
        Map<String, Object> metrics
) {
}
