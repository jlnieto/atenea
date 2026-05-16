package com.atenea.api.operations;

import java.util.List;

public record OperationsIncidentListResponse(
        List<OperationsIncidentResponse> incidents
) {
}
