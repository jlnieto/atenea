package com.atenea.persistence.developmentchange;

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
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V74DevelopmentChangePersistenceRedTest {

    @Test
    void expandsV73WithoutBindingLegacyRowsOrDroppingTheProjectGuard() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(73, flyway(schema, "73").migrate().migrationsExecuted);
            long projectId;
            long legacySessionId;
            String legacyBefore;
            try (Connection connection = connection(schema)) {
                projectId = insertProject(connection, "synthetic-v74-legacy");
                legacySessionId = insertLegacySession(
                        connection, projectId, "atenea/session-900001");
                legacyBefore = legacyProjection(connection, legacySessionId);
            }

            assertEquals(1, flyway(schema, "74").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("74", latestFlywayVersion(connection));
                assertTrue(tableExists(connection, "development_change"));
                assertTrue(columnExists(connection, "work_session", "development_change_id"));
                assertEquals("YES", columnNullable(
                        connection, "work_session", "development_change_id"));
                assertTrue(indexExists(connection, "uk_work_session_active_project"));
                assertTrue(indexExists(connection, "uk_work_session_active_change"));
                assertEquals(legacyBefore, legacyProjection(connection, legacySessionId));
                assertEquals(0, queryLong(connection, """
                        SELECT count(*) FROM work_session
                        WHERE id = ? AND development_change_id IS NOT NULL
                        """, legacySessionId));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM development_change"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM work_session WHERE id = 19"));
            }
        });
    }

    @Test
    void changeOwnershipAllowsTwoBranchesAndRejectsEveryCollision() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(74, flyway(schema, "74").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                long projectId = insertProject(connection, "synthetic-v74-multibranch");
                UUID firstKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
                UUID secondKey = UUID.fromString("17f120f6-79e2-49e4-bd13-23db520d1374");
                insertChange(connection, projectId, firstKey, "a".repeat(64));
                insertChange(connection, projectId, secondKey, "b".repeat(64));

                assertEquals(2, queryLong(connection,
                        "SELECT count(*) FROM development_change WHERE project_id = ?", projectId));
                assertSqlRejected(connection, changeInsertSql(),
                        UUID.fromString("61552669-4b46-431c-811d-344293ab3c67"),
                        projectId,
                        "atenea/change-" + firstKey,
                        "remote:synthetic-worker-01:change:61552669-4b46-431c-811d-344293ab3c67",
                        0L,
                        "c".repeat(64));
                assertSqlRejected(connection, changeInsertSql(),
                        UUID.fromString("af8f979c-96cb-418f-aaf8-9dfc5b8e370b"),
                        projectId,
                        "atenea/change-af8f979c-96cb-418f-aaf8-9dfc5b8e370b",
                        "remote:synthetic-worker-01:change:" + secondKey,
                        0L,
                        "d".repeat(64));
                assertSqlRejected(connection, changeInsertSql(),
                        UUID.fromString("af8f979c-96cb-418f-aaf8-9dfc5b8e370b"),
                        projectId,
                        "atenea/change-af8f979c-96cb-418f-aaf8-9dfc5b8e370b",
                        "remote:synthetic-worker-01:change:af8f979c-96cb-418f-aaf8-9dfc5b8e370b",
                        0L,
                        "not-a-sha256");
                assertSqlRejected(connection, changeInsertSql(),
                        UUID.fromString("af8f979c-96cb-418f-aaf8-9dfc5b8e370b"),
                        projectId,
                        "atenea/change-af8f979c-96cb-418f-aaf8-9dfc5b8e370b",
                        "remote:synthetic-worker-01:change:af8f979c-96cb-418f-aaf8-9dfc5b8e370b",
                        -1L,
                        "d".repeat(64));
            }
        });
    }

    @Test
    void activeSessionIsolationIsAddedPerChangeWhileLegacyGuardRemains() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(74, flyway(schema, "74").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                for (String column : new String[]{
                        "change_key",
                        "project_id",
                        "base_ref",
                        "base_commit",
                        "workspace_branch",
                        "workspace_identity",
                        "project_policy_revision",
                        "source_revision",
                        "source_fingerprint_sha256",
                        "source_state",
                        "validation_state",
                        "review_state",
                        "integration_state",
                        "release_state",
                        "version"}) {
                    assertTrue(columnExists(connection, "development_change", column), column);
                }
                assertTrue(indexExists(connection,
                        "uk_development_change_project_workspace_branch"));
                assertTrue(indexExists(connection,
                        "uk_development_change_workspace_identity"));
                assertTrue(indexExists(connection, "uk_work_session_active_change"));
                assertTrue(indexExists(connection, "uk_work_session_active_project"));
                assertFalse(indexExists(connection, "uk_work_session_active_legacy_project"));
                String activeChangeDefinition = indexDefinition(
                        connection, "uk_work_session_active_change");
                assertTrue(activeChangeDefinition.contains("development_change_id"));
                assertTrue(activeChangeDefinition.contains("OPEN"));
                assertTrue(activeChangeDefinition.contains("CLOSING"));

                long projectId = insertProject(connection, "synthetic-v74-active-change");
                UUID changeKey = UUID.fromString("8bf60472-3c0e-49aa-99bf-6dc3c7e60eaf");
                long changeId = insertChange(connection, projectId, changeKey, "a".repeat(64));
                insertSession(connection, projectId, changeId, "OPEN", null,
                        "atenea/change-" + changeKey);
                assertSqlRejected(connection, sessionInsertSql(),
                        projectId, changeId, "OPEN", "atenea/change-" + changeKey, null);
                insertSession(connection, projectId, changeId, "CLOSED",
                        "2026-08-20T18:00:00Z", "atenea/change-" + changeKey);
                assertEquals(1, queryLong(connection, """
                        SELECT count(*) FROM work_session
                        WHERE development_change_id = ? AND status IN ('OPEN', 'CLOSING')
                        """, changeId));
                assertEquals(2, queryLong(connection, """
                        SELECT count(*) FROM work_session WHERE development_change_id = ?
                        """, changeId));
            }
        });
    }

    private long insertProject(Connection connection, String name) throws SQLException {
        return queryLong(connection, """
                INSERT INTO project (name, repo_path, default_base_branch)
                VALUES (?, '/tmp/synthetic-m2', 'main')
                RETURNING id
                """, name);
    }

    private long insertChange(
            Connection connection,
            long projectId,
            UUID changeKey,
            String fingerprint
    ) throws SQLException {
        return queryLong(connection, changeInsertSql(),
                changeKey,
                projectId,
                "atenea/change-" + changeKey,
                "remote:synthetic-worker-01:change:" + changeKey,
                0L,
                fingerprint);
    }

    private String changeInsertSql() {
        return """
                INSERT INTO development_change (
                    change_key, project_id, title, status, base_ref, base_commit,
                    workspace_branch, workspace_identity, selected_worker_id,
                    project_policy_revision, source_revision,
                    source_fingerprint_sha256, created_at, updated_at)
                VALUES (?, ?, 'Synthetic M2 change', 'OPEN', 'refs/heads/main',
                    '1111111111111111111111111111111111111111', ?, ?,
                    'synthetic-worker-01', 1, ?, ?,
                    '2026-08-20T17:00:00Z', '2026-08-20T17:00:00Z')
                RETURNING id
                """;
    }

    private long insertSession(
            Connection connection,
            long projectId,
            Long changeId,
            String status,
            String closedAt,
            String branch
    ) throws SQLException {
        return queryLong(connection, sessionInsertSql(),
                projectId, changeId, status, branch, closedAt);
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
                    '2026-08-20T17:00:00Z', '2026-08-20T17:00:00Z',
                    'LOCAL', 'local:synthetic-retained-legacy', 'NOT_CREATED',
                    'DRAFT', 'NOT_REQUIRED',
                    '2026-08-20T17:00:00Z', '2026-08-20T17:00:00Z')
                RETURNING id
                """, projectId, branch);
    }

    private String sessionInsertSql() {
        return """
                INSERT INTO work_session (
                    project_id, development_change_id, status, title,
                    base_branch, workspace_branch, opened_at, last_activity_at,
                    closed_at, execution_target, workspace_identity,
                    pull_request_status, acceptance_state, remote_close_state,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Synthetic M2 session', 'main', ?,
                    '2026-08-20T17:00:00Z', '2026-08-20T17:00:00Z', ?::timestamptz,
                    'LOCAL', 'local:synthetic-m2-session', 'NOT_CREATED',
                    'DRAFT', 'NOT_REQUIRED',
                    '2026-08-20T17:00:00Z', '2026-08-20T17:00:00Z')
                RETURNING id
                """;
    }

    private String legacyProjection(Connection connection, long sessionId) throws SQLException {
        return queryString(connection, """
                SELECT ROW(project_id, status, title, base_branch, workspace_branch,
                    opened_at, last_activity_at, closed_at, execution_target,
                    workspace_identity, pull_request_status, acceptance_state,
                    remote_close_state, created_at, updated_at)::text
                FROM work_session WHERE id = ?
                """, sessionId);
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v74_red_" + UUID.randomUUID().toString().replace("-", "");
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

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = ?
                """, table) == 1;
    }

    private boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ? AND column_name = ?
                """, table, column) == 1;
    }

    private String columnNullable(Connection connection, String table, String column)
            throws SQLException {
        return queryString(connection, """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ? AND column_name = ?
                """, table, column);
    }

    private boolean indexExists(Connection connection, String index) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index) == 1;
    }

    private String indexDefinition(Connection connection, String index) throws SQLException {
        return queryString(connection, """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index);
    }

    private void assertSqlRejected(Connection connection, String sql, Object... parameters) {
        SQLException failure = assertThrows(SQLException.class,
                () -> queryLong(connection, sql, parameters));
        assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                "Expected integrity rejection but received SQLSTATE " + failure.getSQLState());
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
}
