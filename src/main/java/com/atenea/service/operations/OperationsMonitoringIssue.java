package com.atenea.service.operations;

public record OperationsMonitoringIssue(String key, String message, boolean confirmSlowResponse) {
}
