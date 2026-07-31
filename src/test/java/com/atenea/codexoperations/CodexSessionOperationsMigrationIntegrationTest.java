package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "atenea.auth.bootstrap.enabled=false")
class CodexSessionOperationsMigrationIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CodexSessionOperationsProperties properties;

    @Test
    void appliesV57ThroughV60AdditivelyWithAllCapabilitiesDefaultOff() {
        List<String> versions = jdbcTemplate.query(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE version IN ('57', '58', '59', '60') ORDER BY installed_rank",
                (resultSet, row) -> resultSet.getString(1));
        assertEquals(List.of("57", "58", "59", "60"), versions);

        for (String table : List.of(
                "worker_codex_catalog",
                "worker_codex_model",
                "worker_codex_model_effort",
                "agent_run_progress_event",
                "agent_run_recovery_operation",
                "notification_event",
                "notification_delivery")) {
            assertTrue(relationExists(table), table);
        }

        for (String column : List.of(
                "agent_run.codex_model_id",
                "agent_run.progress_next_sequence",
                "agent_run.retry_of_run_id",
                "work_session.default_codex_model_id",
                "operator_account.codex_operations_role")) {
            String[] identity = column.split("\\.", 2);
            assertTrue(columnExists(identity[0], identity[1]), column);
        }

        for (String constraint : List.of(
                "ck_agent_run_codex_profile_complete",
                "uk_agent_run_progress_event_sequence",
                "uk_agent_run_recovery_idempotency",
                "uk_notification_event_source",
                "uk_notification_delivery_owner")) {
            assertTrue(constraintExists(constraint), constraint);
        }

        assertFalse(properties.isProfilesEnabled());
        assertFalse(properties.isProgressEnabled());
        assertFalse(properties.isRecoveryEnabled());
        assertFalse(properties.isNotificationOutboxEnabled());
        assertFalse(properties.isManagedUpdatesEnabled());
    }

    private boolean relationExists(String table) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count == 1;
    }

    private boolean constraintExists(String constraint) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?",
                Integer.class,
                constraint);
        return count != null && count == 1;
    }
}
