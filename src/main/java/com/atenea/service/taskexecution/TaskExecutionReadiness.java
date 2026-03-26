package com.atenea.service.taskexecution;

public record TaskExecutionReadiness(
        boolean launchReady,
        String reason
) {
}
