package com.atenea.api.operations;

import com.atenea.persistence.operations.OperationsActionRunStatus;
import java.time.Instant;

public record OperationsActionRunResponse(
        Long id,
        Long incidentId,
        Long hostId,
        Long serviceId,
        String action,
        OperationsActionRunStatus status,
        Integer exitCode,
        String stdoutSummary,
        String stderrSummary,
        OperationsExecutionReportResponse report,
        Instant startedAt,
        Instant finishedAt
) {
}
