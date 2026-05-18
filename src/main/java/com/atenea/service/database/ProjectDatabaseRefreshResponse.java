package com.atenea.service.database;

import com.atenea.persistence.database.ProjectDatabaseRefreshStatus;
import java.time.Instant;

public record ProjectDatabaseRefreshResponse(
        Long id,
        Long projectId,
        ProjectDatabaseRefreshStatus status,
        String runtimeContractPath,
        String databaseEngine,
        String localDatabase,
        String sourceHost,
        String sourceDatabase,
        String decisionBrief,
        String technicalSummary,
        String blockerType,
        String blockerSummary,
        String recommendedAction,
        Integer commandExitCode,
        String commandOutputSummary,
        Long durationMillis,
        Instant startedAt,
        Instant finishedAt
) {
}
