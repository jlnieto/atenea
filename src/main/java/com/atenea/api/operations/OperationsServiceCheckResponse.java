package com.atenea.api.operations;

public record OperationsServiceCheckResponse(
        ManagedHostResponse host,
        ManagedServiceResponse service,
        OperationsActionRunResponse actionRun,
        OperationsIncidentResponse incident
) {
}
