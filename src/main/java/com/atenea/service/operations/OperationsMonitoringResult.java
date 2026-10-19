package com.atenea.service.operations;

import java.util.List;

public record OperationsMonitoringResult(
        Long hostId,
        String hostName,
        List<OperationsMonitoringIssue> issues,
        Long checkRunId
) {
    public OperationsMonitoringResult(Long hostId,String hostName,List<OperationsMonitoringIssue> issues) {
        this(hostId,hostName,issues,null);
    }
    public OperationsMonitoringResult {
        issues = List.copyOf(issues);
    }

    public List<String> errors() {
        return issues.stream().map(OperationsMonitoringIssue::message).toList();
    }

    public boolean degraded() {
        return !issues.isEmpty();
    }
}
