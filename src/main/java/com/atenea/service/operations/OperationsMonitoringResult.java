package com.atenea.service.operations;

import java.util.List;

public record OperationsMonitoringResult(
        Long hostId,
        String hostName,
        List<String> errors
) {
    public OperationsMonitoringResult {
        errors = List.copyOf(errors);
    }

    public boolean degraded() {
        return !errors.isEmpty();
    }
}
