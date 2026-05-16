package com.atenea.api.operations;

import java.util.List;

public record OperationsRecoveryResponse(
        ManagedHostResponse host,
        ManagedServiceResponse service,
        OperationsActionRunResponse actionRun,
        OperationsIncidentResponse incident,
        List<WebsiteCheckResponse> validationChecks
) {
}
