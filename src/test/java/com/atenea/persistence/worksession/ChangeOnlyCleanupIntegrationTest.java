package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChangeOnlyCleanupIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "test_exact_purge_keeps_real_ticket_and_guards",
        "test_idempotent_plan_after_purge",
        "test_rejects_changed_trial_identity",
        "test_rejects_unreviewed_standalone_session",
        "test_rejects_active_execution",
        "test_rejects_cross_owned_retry",
        "test_rejects_cross_owned_usage_without_fk",
        "test_rejects_new_dependent_table",
        "test_postcondition_failure_rolls_back_everything",
        "test_scoped_permission_does_not_delete_real_receipt",
        "test_rejects_disabled_immutability_guard",
        "test_legacy_consumption_cycle_is_deleted_without_fk_bypass",
        "test_all_four_sealed_journals_remain_protected",
        "test_rejects_client_paths_and_commands",
        "test_backup_failure_prevents_all_mutation",
        "test_exact_numeric_retained_values_cannot_drift",
        "test_lost_commit_acknowledgement_is_reconciled_without_second_purge",
        "test_commit_reconciliation_rejects_changed_backup",
        "test_snapshot_backup_is_structurally_readable_in_pg16"
    })
    void verifiesClosedTrialCleanupInPostgreSQL16(String test) throws Exception {
        assertTrue(env("SPRING_DATASOURCE_URL").matches(
                "jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"),
                "Cleanup fixtures require the synthetic test database before creating any schema");
        assertEquals("atenea", env("SPRING_DATASOURCE_USERNAME"));
        assertEquals("atenea", env("SPRING_DATASOURCE_PASSWORD"));
        String schema = "change_only_test_" + UUID.randomUUID().toString().replace("-", "");
        Path log = Files.createTempFile("change-only-cleanup-test-", ".log");
        Process process = null;
        try {
            Flyway.configure().dataSource(env("SPRING_DATASOURCE_URL"),
                    env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).createSchemas(true)
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("93"))
                .load().migrate();
            ProcessBuilder command = new ProcessBuilder("/usr/bin/python3",
                    "scripts/test-change-only-cleanup.py", "CleanupTest." + test);
            command.environment().put("ATENEA_CLEANUP_TEST_SCHEMA", schema);
            command.redirectErrorStream(true).redirectOutput(log.toFile());
            process = command.start();
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Isolated cleanup test timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            try (var connection = DriverManager.getConnection(env("SPRING_DATASOURCE_URL"),
                    env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD"));
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
            Files.deleteIfExists(log);
        }
    }

    private String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required for isolated PostgreSQL tests");
        }
        return value;
    }
}
