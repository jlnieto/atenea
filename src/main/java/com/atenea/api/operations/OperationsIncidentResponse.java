package com.atenea.api.operations;

import com.atenea.persistence.operations.OperationsIncidentStatus;
import com.atenea.persistence.operations.OperationsSeverity;
import java.time.Instant;

public record OperationsIncidentResponse(
        Long id,
        Long hostId,
        String hostName,
        Long serviceId,
        String serviceName,
        OperationsIncidentStatus status,
        OperationsSeverity severity,
        String title,
        String summary,
        Instant openedAt,
        Instant lastActivityAt,
        Instant resolvedAt
) {
}
