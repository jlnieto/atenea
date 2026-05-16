package com.atenea.api.operations;

import java.util.List;

public record OperationsHostStatusResponse(
        ManagedHostResponse host,
        OperationsActionRunResponse hostStatusRun,
        List<ManagedServiceResponse> services,
        List<WebsiteCheckResponse> websiteChecks,
        List<OperationsIncidentResponse> openIncidents
) {
}
