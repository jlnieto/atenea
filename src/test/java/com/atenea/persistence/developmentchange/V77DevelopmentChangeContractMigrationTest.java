package com.atenea.persistence.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V77DevelopmentChangeContractMigrationTest {

    @Test
    void contractsTheGlobalGuardWithoutMutatingRetainedRows() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(76, flyway(schema, "76").migrate().migrationsExecuted);
            long projectId;
            long legacySessionId;
            String legacyBefore;
            long boundSessionId;
            String boundBefore;
            try (Connection connection = connection(schema)) {
                insertSyntheticWorker(connection);
                projectId = insertProject(connection, "synthetic-v77-retained");
                legacySessionId = insertLegacySession(
                        connection, projectId, "atenea/session-retained-v77");
                legacyBefore = sessionProjection(connection, legacySessionId);
                long boundProjectId = insertProject(
                        connection, "synthetic-v77-retained-bound");
                UUID boundKey = UUID.fromString(
                        "d54dfa99-6f95-4f17-a2fc-e6f3d0a4a516");
                long boundChangeId = insertChange(
                        connection, boundProjectId, boundKey, "d".repeat(64));
                boundSessionId = insertBoundSession(
                        connection, boundProjectId, boundChangeId, boundKey, "OPEN");
                boundBefore = sessionProjection(connection, boundSessionId);
            }

            assertEquals(1, flyway(schema, "77").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("77", latestFlywayVersion(connection));
                assertFalse(indexExists(connection, "uk_work_session_active_project"));
                assertTrue(indexExists(connection, "uk_work_session_active_change"));
                assertTrue(indexExists(
                        connection, "uk_work_session_active_legacy_project"));
                assertTrue(indexDefinition(
                        connection, "uk_work_session_active_legacy_project")
                        .contains("development_change_id IS NULL"));
                assertEquals(legacyBefore, sessionProjection(connection, legacySessionId));
                assertEquals(boundBefore, sessionProjection(connection, boundSessionId));
                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM development_change"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM work_session WHERE id = 19"));
            }
        });
    }

    @Test
    void permitsIndependentActiveChangesWhileKeepingBothRaceSafeGuards()
            throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(77, flyway(schema, "77").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                insertSyntheticWorker(connection);
                long projectId = insertProject(connection, "synthetic-v77-isolation");
                long legacySessionId = insertLegacySession(
                        connection, projectId, "atenea/session-legacy-v77");
                UUID firstKey = UUID.fromString("b99dd40b-00fa-4f53-aef2-c22d61953bb5");
                UUID secondKey = UUID.fromString("b2c93612-1b75-49b4-b51c-91be683d6e5a");
                long firstChangeId = insertChange(connection, projectId, firstKey, "a".repeat(64));
                long secondChangeId = insertChange(connection, projectId, secondKey, "b".repeat(64));
                insertBoundSession(connection, projectId, firstChangeId, firstKey, "OPEN");
                insertBoundSession(connection, projectId, secondChangeId, secondKey, "CLOSING");

                assertEquals(3, queryLong(connection, """
                        SELECT count(*) FROM work_session
                        WHERE project_id = ? AND status IN ('OPEN', 'CLOSING')
                        """, projectId));
                assertEquals(1, queryLong(connection, """
                        SELECT count(*) FROM work_session
                        WHERE id = ? AND development_change_id IS NULL
                        """, legacySessionId));

                assertIntegrityRejected(connection, () -> insertLegacySession(
                        connection, projectId, "atenea/session-legacy-v77-duplicate"));
                assertIntegrityRejected(connection, () -> insertBoundSession(
                        connection, projectId, firstChangeId, firstKey, "OPEN"));
            }
        });
    }

    @Test
    void rejectsDuplicateLegacyRowsBeforeDroppingTheGlobalGuard() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(76, flyway(schema, "76").migrate().migrationsExecuted);
            long projectId;
            try (Connection connection = connection(schema)) {
                projectId = insertProject(connection, "synthetic-v77-duplicate-legacy");
                execute(connection, "DROP INDEX uk_work_session_active_project");
                insertLegacySession(connection, projectId, "atenea/session-legacy-v77-a");
                insertLegacySession(connection, projectId, "atenea/session-legacy-v77-b");
                execute(connection, """
                        CREATE UNIQUE INDEX uk_work_session_active_project
                        ON work_session (project_id)
                        WHERE status IN ('OPEN', 'CLOSING') AND FALSE
                        """);
            }

            assertThrows(FlywayException.class, () -> flyway(schema, "77").migrate());

            try (Connection connection = connection(schema)) {
                assertEquals("76", latestFlywayVersion(connection));
                assertTrue(indexExists(connection, "uk_work_session_active_project"));
                assertFalse(indexExists(
                        connection, "uk_work_session_active_legacy_project"));
                assertEquals(2, queryLong(connection, """
                        SELECT count(*) FROM work_session
                        WHERE project_id = ? AND development_change_id IS NULL
                          AND status IN ('OPEN', 'CLOSING')
                        """, projectId));
            }
        });
    }

    @Test
    void rejectsDivergentIsolationIndexWithoutDroppingTheGlobalGuard() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(76, flyway(schema, "76").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                execute(connection, "DROP INDEX uk_work_session_active_change");
                execute(connection, """
                        CREATE INDEX uk_work_session_active_change
                        ON work_session (development_change_id)
                        WHERE development_change_id IS NOT NULL
                        """);
            }

            assertThrows(FlywayException.class, () -> flyway(schema, "77").migrate());

            try (Connection connection = connection(schema)) {
                assertEquals("76", latestFlywayVersion(connection));
                assertTrue(indexExists(connection, "uk_work_session_active_project"));
                assertFalse(indexExists(
                        connection, "uk_work_session_active_legacy_project"));
            }
        });
    }

    @Test
    void rejectsIncompleteBoundOwnershipWithoutChangingDataOrIndexes() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(76, flyway(schema, "76").migrate().migrationsExecuted);
            long sessionId;
            String before;
            try (Connection connection = connection(schema)) {
                insertSyntheticWorker(connection);
                long projectId = insertProject(connection, "synthetic-v77-ownership");
                UUID changeKey = UUID.fromString("4843484b-3d9f-4c3b-a434-7b79c25ed92e");
                long changeId = insertChange(connection, projectId, changeKey, "c".repeat(64));
                sessionId = insertMismatchedBoundSession(
                        connection, projectId, changeId, "atenea/change-wrong");
                before = sessionProjection(connection, sessionId);
            }

            assertThrows(FlywayException.class, () -> flyway(schema, "77").migrate());

            try (Connection connection = connection(schema)) {
                assertEquals("76", latestFlywayVersion(connection));
                assertTrue(indexExists(connection, "uk_work_session_active_project"));
                assertFalse(indexExists(
                        connection, "uk_work_session_active_legacy_project"));
                assertEquals(before, sessionProjection(connection, sessionId));
            }
        });
    }

    @Test
    void migrationContainsNoBackfillOrDestructiveDataStatement() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V77__contract_development_change_session_isolation.sql"))
                .toUpperCase(Locale.ROOT);

        assertFalse(sql.matches("(?s).*\\b(INSERT|UPDATE|DELETE|TRUNCATE)\\b.*"));
        assertFalse(sql.contains("DROP TABLE"));
        assertFalse(sql.contains("DROP COLUMN"));
        assertTrue(sql.indexOf("CREATE UNIQUE INDEX UK_WORK_SESSION_ACTIVE_LEGACY_PROJECT")
                < sql.indexOf("DROP INDEX UK_WORK_SESSION_ACTIVE_PROJECT"));
        assertTrue(sql.contains("LOCK TABLE WORK_SESSION IN SHARE ROW EXCLUSIVE MODE"));
    }

    private long insertProject(Connection connection, String name) throws SQLException {
        return queryLong(connection, """
                INSERT INTO project (name, repo_path, default_base_branch)
                VALUES (?, '/tmp/synthetic-m2', 'main')
                RETURNING id
                """, name);
    }

    private void insertSyntheticWorker(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO worker_node (
                    id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, normal_in_use, heavy_in_use,
                    capabilities)
                VALUES ('synthetic-worker-01', 'agent-run-worker/v1',
                    'https://worker.invalid', FALSE, FALSE, 2, 1, 0, 0,
                    'project-codex-v1')
                """);
    }

    private long insertChange(
            Connection connection,
            long projectId,
            UUID changeKey,
            String fingerprint
    ) throws SQLException {
        return queryLong(connection, """
                INSERT INTO development_change (
                    change_key, project_id, title, status, base_ref, base_commit,
                    workspace_branch, workspace_identity, selected_worker_id,
                    project_policy_revision, source_revision,
                    source_fingerprint_sha256, created_at, updated_at)
                VALUES (?, ?, 'Synthetic V77 change', 'OPEN', 'refs/heads/main',
                    '1111111111111111111111111111111111111111', ?, ?,
                    'synthetic-worker-01', 1, 0, ?,
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z')
                RETURNING id
                """,
                changeKey,
                projectId,
                "atenea/change-" + changeKey,
                "remote:synthetic-worker-01:change:" + changeKey,
                fingerprint);
    }

    private long insertLegacySession(
            Connection connection,
            long projectId,
            String branch
    ) throws SQLException {
        return queryLong(connection, """
                INSERT INTO work_session (
                    project_id, status, title, base_branch, workspace_branch,
                    opened_at, last_activity_at, execution_target,
                    workspace_identity, pull_request_status, acceptance_state,
                    remote_close_state, created_at, updated_at)
                VALUES (?, 'OPEN', 'Synthetic retained legacy session', 'main', ?,
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z',
                    'LOCAL', ?, 'NOT_CREATED', 'DRAFT', 'NOT_REQUIRED',
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z')
                RETURNING id
                """, projectId, branch, "local:" + UUID.randomUUID());
    }

    private long insertBoundSession(
            Connection connection,
            long projectId,
            long changeId,
            UUID changeKey,
            String status
    ) throws SQLException {
        return queryLong(connection, """
                INSERT INTO work_session (
                    project_id, development_change_id, status, title,
                    base_branch, workspace_branch, opened_at, last_activity_at,
                    execution_target, selected_worker_id, workspace_identity,
                    remote_session_id, remote_workload_kind,
                    pull_request_status, acceptance_state, remote_close_state,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Synthetic V77 bound session', 'main', ?,
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z',
                    'REMOTE', 'synthetic-worker-01', ?, ?, 'project-codex-v1',
                    'NOT_CREATED', 'DRAFT', 'NOT_STARTED',
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z')
                RETURNING id
                """,
                projectId,
                changeId,
                status,
                "atenea/change-" + changeKey,
                "remote:synthetic-worker-01:change:" + changeKey,
                UUID.randomUUID());
    }

    private long insertMismatchedBoundSession(
            Connection connection,
            long projectId,
            long changeId,
            String branch
    ) throws SQLException {
        return queryLong(connection, """
                INSERT INTO work_session (
                    project_id, development_change_id, status, title,
                    base_branch, workspace_branch, opened_at, last_activity_at,
                    execution_target, selected_worker_id, workspace_identity,
                    remote_session_id, remote_workload_kind,
                    pull_request_status, acceptance_state, remote_close_state,
                    created_at, updated_at)
                VALUES (?, ?, 'OPEN', 'Synthetic mismatched V77 session', 'main', ?,
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z',
                    'REMOTE', 'synthetic-worker-01', 'remote:synthetic-wrong', ?,
                    'project-codex-v1', 'NOT_CREATED', 'DRAFT', 'NOT_STARTED',
                    '2026-08-20T20:00:00Z', '2026-08-20T20:00:00Z')
                RETURNING id
                """, projectId, changeId, branch, UUID.randomUUID());
    }

    private String sessionProjection(Connection connection, long sessionId)
            throws SQLException {
        return queryString(connection, """
                SELECT ROW(project_id, development_change_id, status, title,
                    base_branch, workspace_branch, opened_at, last_activity_at,
                    closed_at, execution_target, selected_worker_id,
                    workspace_identity, remote_session_id, remote_workload_kind,
                    pull_request_status, acceptance_state, remote_close_state,
                    created_at, updated_at)::text
                FROM work_session WHERE id = ?
                """, sessionId);
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v77_contract_" + UUID.randomUUID().toString().replace("-", "");
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

    private String latestFlywayVersion(Connection connection) throws SQLException {
        return queryString(connection, """
                SELECT version FROM flyway_schema_history
                WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                """);
    }

    private boolean indexExists(Connection connection, String index) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index) == 1;
    }

    private String indexDefinition(Connection connection, String index)
            throws SQLException {
        return queryString(connection, """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertIntegrityRejected(Connection connection, SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                "Expected integrity rejection but received SQLSTATE "
                        + failure.getSQLState());
    }

    private long queryLong(Connection connection, String sql, Object... parameters)
            throws SQLException {
        return ((Number) queryObject(connection, sql, parameters)).longValue();
    }

    private String queryString(Connection connection, String sql, Object... parameters)
            throws SQLException {
        return String.valueOf(queryObject(connection, sql, parameters));
    }

    private Object queryObject(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected one query result");
                Object value = resultSet.getObject(1);
                assertFalse(resultSet.next(), "Expected exactly one query result");
                return value;
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

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
