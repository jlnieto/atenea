package com.atenea.persistence.v2control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V67V2ControlContractsMigrationTest {

    @Test
    void migratesEmptySchemaToV67WithNoEnabledGateOrPolicy() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "67");

            assertEquals(67, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("67", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
                for (String table : new String[]{
                        "v2_global_capability_gate",
                        "v2_project_capability_policy",
                        "v2_audit_event",
                        "v2_outbox_event"}) {
                    assertTrue(tableExists(connection, table), table);
                }
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM v2_global_capability_gate WHERE enabled"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM v2_project_capability_policy WHERE enabled"));
            }
        });
    }

    @Test
    void upgradeFromV66PreservesLegacyRecordBytesAcrossMigrationAndReconnect() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(66, flyway(schema, "66").migrate().migrationsExecuted);
            long projectId;
            long sessionId;
            long operatorTurnId;
            long codexTurnId;
            long runId;
            List<String> beforeMigration;
            try (Connection connection = connection(schema)) {
                projectId = queryLong(connection, """
                        INSERT INTO project (name, repo_path, default_base_branch)
                        VALUES ('synthetic-v66-project', '/tmp/synthetic-v66', 'main')
                        RETURNING id
                        """);
                sessionId = queryLong(connection, """
                        INSERT INTO work_session (
                            project_id, status, title, base_branch, workspace_branch,
                            external_thread_id, opened_at, last_activity_at,
                            execution_target, workspace_identity,
                            pull_request_status, acceptance_state,
                            remote_close_state, created_at, updated_at)
                        VALUES (?, 'OPEN', 'Synthetic retained session', 'main',
                            'codex/synthetic-retained', 'synthetic-thread',
                            '2026-08-01T10:00:00Z', '2026-08-01T10:02:00Z',
                            'LOCAL', 'local:work-session:synthetic-retained',
                            'NOT_CREATED', 'DRAFT', 'NOT_REQUIRED',
                            '2026-08-01T10:00:00Z', '2026-08-01T10:02:00Z')
                        RETURNING id
                        """, projectId);
                operatorTurnId = queryLong(connection, """
                        INSERT INTO session_turn (
                            session_id, actor, message_text, internal, created_at)
                        VALUES (?, 'OPERATOR', 'Synthetic retained request', FALSE,
                            '2026-08-01T10:01:00Z')
                        RETURNING id
                        """, sessionId);
                codexTurnId = queryLong(connection, """
                        INSERT INTO session_turn (
                            session_id, actor, message_text, internal, created_at)
                        VALUES (?, 'CODEX', 'Synthetic retained response', FALSE,
                            '2026-08-01T10:02:00Z')
                        RETURNING id
                        """, sessionId);
                runId = queryLong(connection, """
                        INSERT INTO agent_run (
                            session_id, origin_turn_id, result_turn_id, status,
                            target_repo_path, external_turn_id, started_at, finished_at,
                            output_summary, execution_target, workspace_identity,
                            process_outcome, created_at)
                        VALUES (?, ?, ?, 'SUCCEEDED', '/tmp/synthetic-v66',
                            'synthetic-external-turn', '2026-08-01T10:01:00Z',
                            '2026-08-01T10:02:00Z', 'Synthetic retained success',
                            'LOCAL', 'local:work-session:synthetic-retained',
                            'SUCCEEDED', '2026-08-01T10:01:00Z')
                        RETURNING id
                        """, sessionId, operatorTurnId, codexTurnId);
                beforeMigration = legacyRecordBytes(
                        connection, projectId, sessionId, operatorTurnId, codexTurnId, runId);
            }

            assertEquals(1, flyway(schema, "67").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals(beforeMigration, legacyRecordBytes(
                        connection, projectId, sessionId, operatorTurnId, codexTurnId, runId));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM v2_project_capability_policy WHERE project_id = ?",
                        projectId));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM v2_audit_event WHERE project_id = ?", projectId));
            }

            try (Connection connection = connection(schema)) {
                assertEquals(beforeMigration, legacyRecordBytes(
                        connection, projectId, sessionId, operatorTurnId, codexTurnId, runId));
            }
        });
    }

    @Test
    void defaultsConstraintsAndAppendOnlyBoundaryFailClosed() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "67").migrate();
            try (Connection connection = connection(schema)) {
                long projectId = queryLong(connection, """
                        INSERT INTO project (name, repo_path, default_base_branch)
                        VALUES ('synthetic-v67-project', '/tmp/synthetic-v67', 'main')
                        RETURNING id
                        """);
                long actorId = queryLong(connection, """
                        INSERT INTO operator_account (
                            email, display_name, password_hash, active,
                            created_at, updated_at)
                        VALUES ('v67@atenea.test', 'V67 operator', 'synthetic-hash', TRUE,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        RETURNING id
                        """);

                execute(connection, """
                        INSERT INTO v2_global_capability_gate (
                            capability, created_at, updated_at)
                        VALUES ('control-contracts', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """);
                assertFalse(queryBoolean(connection,
                        "SELECT enabled FROM v2_global_capability_gate WHERE capability = 'control-contracts'"));
                assertEquals(0, queryLong(connection,
                        "SELECT revision FROM v2_global_capability_gate WHERE capability = 'control-contracts'"));

                execute(connection, """
                        INSERT INTO v2_project_capability_policy (
                            project_id, capability, policy_revision, created_at, updated_at)
                        VALUES (?, 'control-contracts', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, projectId);
                assertFalse(queryBoolean(connection, """
                        SELECT enabled FROM v2_project_capability_policy
                        WHERE project_id = ? AND capability = 'control-contracts'
                        """, projectId));
                assertSqlRejected(connection, """
                        INSERT INTO v2_project_capability_policy (
                            project_id, capability, policy_revision, created_at, updated_at)
                        VALUES (?, '*', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, projectId);

                UUID operationId = UUID.randomUUID();
                UUID auditId = UUID.randomUUID();
                execute(connection, """
                        INSERT INTO v2_audit_event (
                            id, operation_id, project_id, actor_id, capability,
                            event_type, state, revision,
                            request_fingerprint_sha256, target_fingerprint_sha256,
                            item_count, duration_millis, occurred_at)
                        VALUES (?, ?, ?, ?, 'control-contracts', 'POLICY_DENIED',
                            'DENIED', 0, ?, ?, 0, 1, CURRENT_TIMESTAMP)
                        """, auditId, operationId, projectId, actorId,
                        "a".repeat(64), "b".repeat(64));
                execute(connection, """
                        INSERT INTO v2_outbox_event (
                            id, audit_event_id, operation_id, capability,
                            event_type, revision, deduplication_sha256,
                            created_at, updated_at)
                        VALUES (?, ?, ?, 'control-contracts', 'POLICY_DENIED', 0, ?,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID(), auditId, operationId, "c".repeat(64));
                assertEquals("PENDING", queryString(connection,
                        "SELECT state FROM v2_outbox_event WHERE audit_event_id = ?", auditId));
                assertEquals(0, queryLong(connection,
                        "SELECT attempt_count FROM v2_outbox_event WHERE audit_event_id = ?", auditId));
                assertSqlRejected(connection,
                        "UPDATE v2_audit_event SET state = 'CHANGED' WHERE id = ?", auditId);
                assertSqlRejected(connection,
                        "DELETE FROM v2_audit_event WHERE id = ?", auditId);
                assertSqlRejected(connection, """
                        INSERT INTO v2_audit_event (
                            id, operation_id, project_id, actor_id, capability,
                            event_type, state, revision,
                            request_fingerprint_sha256, target_fingerprint_sha256,
                            failure_category, failure_code, item_count,
                            duration_millis, occurred_at)
                        VALUES (?, ?, ?, ?, 'control-contracts', 'POLICY_DENIED',
                            'DENIED', 1, ?, ?, 'PROTOCOL', 'INVALID_PROTOCOL',
                            0, 0, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID(), UUID.randomUUID(), projectId, actorId,
                        "d".repeat(64), "e".repeat(64));
            }
        });
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v67_" + UUID.randomUUID().toString().replace("-", "");
        try {
            work.run(schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    requiredEnvironment("SPRING_DATASOURCE_URL"),
                    requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                    requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(requiredEnvironment("SPRING_DATASOURCE_URL"),
                        requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                        requiredEnvironment("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                requiredEnvironment("SPRING_DATASOURCE_URL"),
                requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
        return connection;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = ?
                """, table) == 1;
    }

    private void assertSqlRejected(Connection connection, String sql, Object... parameters) {
        SQLException failure = assertThrows(SQLException.class,
                () -> execute(connection, sql, parameters));
        assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                "Expected integrity rejection but received SQLSTATE " + failure.getSQLState());
    }

    private void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private long queryLong(Connection connection, String sql, Object... parameters) throws SQLException {
        return ((Number) queryObject(connection, sql, parameters)).longValue();
    }

    private boolean queryBoolean(Connection connection, String sql, Object... parameters)
            throws SQLException {
        return (Boolean) queryObject(connection, sql, parameters);
    }

    private String queryString(Connection connection, String sql, Object... parameters)
            throws SQLException {
        return String.valueOf(queryObject(connection, sql, parameters));
    }

    private List<String> legacyRecordBytes(
            Connection connection,
            long projectId,
            long sessionId,
            long operatorTurnId,
            long codexTurnId,
            long runId
    ) throws SQLException {
        return List.of(
                queryString(connection,
                        "SELECT row_to_json(record)::text FROM project record WHERE id = ?",
                        projectId),
                queryString(connection,
                        "SELECT row_to_json(record)::text FROM work_session record WHERE id = ?",
                        sessionId),
                queryString(connection,
                        "SELECT row_to_json(record)::text FROM session_turn record WHERE id = ?",
                        operatorTurnId),
                queryString(connection,
                        "SELECT row_to_json(record)::text FROM session_turn record WHERE id = ?",
                        codexTurnId),
                queryString(connection,
                        "SELECT row_to_json(record)::text FROM agent_run record WHERE id = ?",
                        runId));
    }

    private Object queryObject(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected one row from migration fixture query");
                return resultSet.getObject(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for isolated migration tests");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(String schema) throws Exception;
    }
}
