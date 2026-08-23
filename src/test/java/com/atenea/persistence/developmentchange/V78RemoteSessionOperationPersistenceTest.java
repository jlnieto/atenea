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

class V78RemoteSessionOperationPersistenceTest {

    @Test
    void expandsV77WithoutChangingExistingRowsOrCreatingOperationalResources() throws Exception {
        withSchema(schema -> {
            assertEquals(77, flyway(schema, "77").migrate().migrationsExecuted);
            String before;
            try (Connection connection = connection(schema)) {
                long projectId = insertProject(connection);
                long changeId = insertChange(connection, projectId);
                before = queryString(connection, """
                        SELECT ROW(change_key, project_id, status, workspace_branch,
                            workspace_identity, source_fingerprint_sha256, version)::text
                        FROM development_change WHERE id = ?
                        """, changeId);
            }

            assertEquals(1, flyway(schema, "78").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals(before, queryString(connection, """
                        SELECT ROW(change_key, project_id, status, workspace_branch,
                            workspace_identity, source_fingerprint_sha256, version)::text
                        FROM development_change LIMIT 1
                        """));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM remote_session_operation"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM work_session"));
                assertTrue(indexExists(connection,
                        "uk_remote_session_operation_idempotency"));
                assertTrue(indexExists(connection,
                        "uk_remote_session_operation_active_change"));
            }
        });
    }

    @Test
    void enforcesOneActiveIntentMonotonicTerminalReceiptAndImmutability() throws Exception {
        withSchema(schema -> {
            assertEquals(78, flyway(schema, "78").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                long projectId = insertProject(connection);
                long operatorId = insertOperator(connection);
                long changeId = insertChange(connection, projectId);
                UUID operationId = UUID.randomUUID();
                UUID idempotencyKey = UUID.randomUUID();
                insertRequested(connection, operationId, idempotencyKey,
                        operatorId, projectId, changeId);

                assertSqlRejected(connection, () -> insertRequested(
                        connection, UUID.randomUUID(), UUID.randomUUID(),
                        operatorId, projectId, changeId));
                assertSqlRejected(connection, () -> insertRequested(
                        connection, UUID.randomUUID(), idempotencyKey,
                        operatorId, projectId, changeId));

                assertEquals(1, execute(connection, """
                        UPDATE remote_session_operation
                        SET state = 'BLOCKED', revision = 1,
                            rejection_class = 'OWNERSHIP',
                            failure_code = 'REMOTE_SESSION_RECOVERY_AMBIGUOUS',
                            next_action = 'RESOLVE_OWNERSHIP',
                            receipt_sha256 = ?, completed_at = now(), updated_at = now()
                        WHERE operation_id = ?
                        """, "f".repeat(64), operationId));

                assertSqlRejected(connection, () -> execute(connection, """
                        UPDATE remote_session_operation
                        SET failure_code = 'REMOTE_SESSION_CHANGED'
                        WHERE operation_id = ?
                        """, operationId));
                assertSqlRejected(connection, () -> execute(connection,
                        "DELETE FROM remote_session_operation WHERE operation_id = ?",
                        operationId));
            }
        });
    }

    private void insertRequested(
            Connection connection,
            UUID operationId,
            UUID idempotencyKey,
            long operatorId,
            long projectId,
            long changeId) throws SQLException {
        assertEquals(1, execute(connection, """
                INSERT INTO remote_session_operation (
                    operation_id, operator_id, project_id, development_change_id,
                    idempotency_key, operation_kind, expected_change_revision,
                    request_fingerprint_sha256, target_fingerprint_sha256,
                    source_fingerprint_sha256, ownership_fingerprint_sha256,
                    beta_policy_revision, state, revision, requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN_OR_RESOLVE_REMOTE_SESSION', 0,
                    ?, ?, ?, ?, 1, 'REQUESTED', 0, now(), now())
                """, operationId, operatorId, projectId, changeId, idempotencyKey,
                "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));
    }

    private long insertProject(Connection connection) throws SQLException {
        return queryLong(connection, """
                INSERT INTO project (name, repo_path, default_base_branch)
                VALUES (?, '/tmp/synthetic-m25-v78', 'main') RETURNING id
                """, "m25-v78-" + UUID.randomUUID());
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
                    source_fingerprint_sha256, workspace_state,
                    workspace_operation_revision, workspace_observation_sha256,
                    workspace_updated_at, created_at, updated_at)
                VALUES (?, ?, 'Synthetic V78', 'OPEN', 'refs/heads/main', ?, ?, ?,
                    'synthetic-worker-01', 7, 0, ?, 'READY', 1, ?, now(), now(), now())
                RETURNING id
                """, key, projectId, "1".repeat(40),
                "atenea/change-" + key,
                "remote:synthetic-worker-01:change:" + key,
                "a".repeat(64), "b".repeat(64));
    }

    private void withSchema(SqlWork work) throws Exception {
        String schema = "v78_remote_session_"
                + UUID.randomUUID().toString().replace("-", "");
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
        return ((Number) queryObject(connection, sql, arguments)).longValue();
    }

    private String queryString(Connection connection, String sql, Object... arguments)
            throws SQLException {
        return String.valueOf(queryObject(connection, sql, arguments));
    }

    private Object queryObject(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, arguments);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                Object value = result.getObject(1);
                assertTrue(!result.next());
                return value;
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
