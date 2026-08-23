package com.atenea.persistence.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class V76DevelopmentChangeWorkspaceOperationPersistenceTest {

    @Test
    void expandsV75WithoutProvisioningOrChangingRetainedIdentity() throws Exception {
        withSchema(schema -> {
            assertEquals(75, flyway(schema, "75").migrate().migrationsExecuted);
            String before;
            try (Connection connection = connection(schema)) {
                long projectId = insertProject(connection);
                long changeId = insertChange(connection, projectId);
                before = queryString(connection, """
                        SELECT ROW(change_key, project_id, base_ref, base_commit,
                            workspace_branch, workspace_identity, source_revision,
                            source_fingerprint_sha256, version)::text
                        FROM development_change WHERE id = ?
                        """, changeId);
            }

            assertEquals(1, flyway(schema, "76").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals(before, queryString(connection, """
                        SELECT ROW(change_key, project_id, base_ref, base_commit,
                            workspace_branch, workspace_identity, source_revision,
                            source_fingerprint_sha256, version)::text
                        FROM development_change LIMIT 1
                        """));
                assertEquals("NOT_PROVISIONED", queryString(connection,
                        "SELECT workspace_state FROM development_change LIMIT 1"));
                assertEquals(0, queryLong(connection,
                        "SELECT workspace_operation_revision FROM development_change LIMIT 1"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM development_change_workspace_operation"));
                assertTrue(indexExists(connection,
                        "uk_development_change_workspace_active"));
                assertTrue(indexExists(connection,
                        "uk_development_change_workspace_predecessor"));
                assertTrue(indexExists(connection, "uk_work_session_active_project"));
            }
        });
    }

    @Test
    void enforcesSingleActiveOperationLinearReconciliationAndTerminalImmutability()
            throws Exception {
        withSchema(schema -> {
            assertEquals(76, flyway(schema, "76").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                long projectId = insertProject(connection);
                long operatorId = insertOperator(connection);
                long changeId = insertChange(connection, projectId);
                UUID provisionId = UUID.randomUUID();
                UUID provisionKey = UUID.randomUUID();
                insertRequested(connection, provisionId, provisionKey,
                        "PROVISION", null, operatorId, projectId, changeId);

                assertSqlRejected(connection, () -> insertRequested(
                        connection, UUID.randomUUID(), UUID.randomUUID(),
                        "INSPECT", null, operatorId, projectId, changeId));

                dispatch(connection, provisionId);
                terminal(connection, provisionId, "UNCERTAIN", "UNCERTAIN",
                        "TRANSPORT", "DEVELOPMENT_CHANGE_WORKER_RESPONSE_UNCERTAIN");

                UUID reconcileId = UUID.randomUUID();
                insertRequested(connection, reconcileId, UUID.randomUUID(),
                        "RECONCILE", provisionId, operatorId, projectId, changeId);
                assertSqlRejected(connection, () -> insertRequested(
                        connection, UUID.randomUUID(), UUID.randomUUID(),
                        "RECONCILE", provisionId, operatorId, projectId, changeId));
                assertSqlRejected(connection, () -> insertRequested(
                        connection, UUID.randomUUID(), UUID.randomUUID(),
                        "RECONCILE", UUID.randomUUID(), operatorId, projectId, changeId));

                dispatch(connection, reconcileId);
                terminal(connection, reconcileId, "SUCCEEDED", "READY", null, null);
                assertSqlRejected(connection, () -> execute(connection, """
                        UPDATE development_change_workspace_operation
                        SET receipt_sha256 = ? WHERE operation_id = ?
                        """, "f".repeat(64), reconcileId));
                assertSqlRejected(connection, () -> execute(connection, """
                        DELETE FROM development_change_workspace_operation
                        WHERE operation_id = ?
                        """, reconcileId));
            }
        });
    }

    private void insertRequested(
            Connection connection,
            UUID operationId,
            UUID idempotencyKey,
            String kind,
            UUID predecessor,
            long operatorId,
            long projectId,
            long changeId) throws SQLException {
        assertEquals(1, execute(connection, """
                INSERT INTO development_change_workspace_operation (
                    operation_id, operator_id, project_id, development_change_id,
                    idempotency_key, operation_kind, predecessor_operation_id,
                    request_fingerprint_sha256, target_fingerprint_sha256,
                    expected_source_revision, expected_source_fingerprint_sha256,
                    expected_canonical_commit, state, revision,
                    requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'REQUESTED', 0,
                    now(), now())
                """, operationId, operatorId, projectId, changeId,
                idempotencyKey, kind, predecessor,
                "b".repeat(64), "c".repeat(64), "a".repeat(64), "1".repeat(40)));
    }

    private void dispatch(Connection connection, UUID operationId) throws SQLException {
        assertEquals(1, execute(connection, """
                UPDATE development_change_workspace_operation
                SET state = 'DISPATCHED', revision = 1,
                    dispatched_at = now(), updated_at = now()
                WHERE operation_id = ?
                """, operationId));
    }

    private void terminal(
            Connection connection,
            UUID operationId,
            String state,
            String workspaceState,
            String category,
            String code) throws SQLException {
        assertEquals(1, execute(connection, """
                UPDATE development_change_workspace_operation
                SET state = ?, revision = 2,
                    result_workspace_state = ?, result_source_state = 'CLEAN',
                    result_source_revision = 0,
                    result_source_fingerprint_sha256 = ?,
                    observed_canonical_commit = ?,
                    failure_category = ?, failure_code = ?, receipt_sha256 = ?,
                    completed_at = now(), updated_at = now()
                WHERE operation_id = ?
                """, state, workspaceState, "a".repeat(64), "1".repeat(40),
                category, code, "d".repeat(64), operationId));
    }

    private long insertProject(Connection connection) throws SQLException {
        return queryLong(connection, """
                INSERT INTO project (name, repo_path, default_base_branch)
                VALUES (?, '/tmp/synthetic-m2-v76', 'main') RETURNING id
                """, "m2-v76-" + UUID.randomUUID());
    }

    private long insertOperator(Connection connection) throws SQLException {
        return queryLong(connection, """
                INSERT INTO operator_account (
                    email, display_name, password_hash, active, created_at, updated_at)
                VALUES (?, 'Synthetic', 'synthetic-hash', true, now(), now())
                RETURNING id
                """, UUID.randomUUID() + "@atenea.test");
    }

    private long insertChange(Connection connection, long projectId) throws SQLException {
        UUID key = UUID.randomUUID();
        return queryLong(connection, """
                INSERT INTO development_change (
                    change_key, project_id, title, status, base_ref, base_commit,
                    workspace_branch, workspace_identity, selected_worker_id,
                    project_policy_revision, source_revision,
                    source_fingerprint_sha256, created_at, updated_at)
                VALUES (?, ?, 'Synthetic V76', 'OPEN', 'refs/heads/main', ?, ?, ?,
                    'ax42-01', 1, 0, ?, now(), now())
                RETURNING id
                """, key, projectId, "1".repeat(40),
                "atenea/change-" + key,
                "remote:ax42-01:change:" + key,
                "a".repeat(64));
    }

    private void withSchema(SqlWork work) throws Exception {
        String schema = "v76_workspace_" + UUID.randomUUID().toString().replace("-", "");
        try {
            work.run(schema);
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    required("SPRING_DATASOURCE_URL"),
                    required("SPRING_DATASOURCE_USERNAME"),
                    required("SPRING_DATASOURCE_PASSWORD"));
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(required("SPRING_DATASOURCE_URL"),
                        required("SPRING_DATASOURCE_USERNAME"),
                        required("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
        return connection;
    }

    private boolean indexExists(Connection connection, String name) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, name) == 1;
    }

    private void assertSqlRejected(Connection connection, SqlRunnable action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                "Expected integrity rejection but received SQLSTATE " + failure.getSQLState());
    }

    private int execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            return statement.executeUpdate();
        }
    }

    private long queryLong(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Expected one row");
                }
                return result.getLong(1);
            }
        }
    }

    private String queryString(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Expected one row");
                }
                return result.getString(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object... arguments) throws SQLException {
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(String schema) throws Exception;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}
