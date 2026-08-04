package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class V63RemoteCloseLifecycleMigrationTest {

    private static final String BRANCH = "main";

    @Test
    void migratesEmptySchemaToV63AndSecondMigrationIsNoOp() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "63");

            assertEquals(63, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("63", queryString(connection, """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = TRUE
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """));
                for (String column : new String[]{
                        "work_session.remote_close_state",
                        "work_session.remote_close_operation_id",
                        "work_session.remote_close_revision",
                        "work_session.remote_close_receipt_sha256",
                        "work_session.remote_close_error_code",
                        "work_session.remote_close_requested_at",
                        "work_session.remote_close_updated_at",
                        "work_session.remote_close_released_at",
                        "agent_run.failure_code",
                        "agent_run.recovery_next_action"}) {
                    String[] identity = column.split("\\.", 2);
                    assertTrue(columnExists(connection, identity[0], identity[1]), column);
                }
                assertTrue(indexExists(connection, "idx_work_session_remote_close_reconcile"));
                assertTrue(indexExists(connection, "idx_agent_run_failure_recovery"));
                assertTrue(tableExists(connection, "remote_close_legacy_plan"));
                assertTrue(tableExists(connection, "remote_close_legacy_operation"));
                assertTrue(indexExists(connection, "idx_remote_close_legacy_plan_session"));
                assertTrue(indexExists(connection, "idx_remote_close_legacy_operation_session"));
            }
        });
    }

    @Test
    void backfillsLegacyRowsWithoutClaimingHistoricalRemoteRelease() throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(62, flyway(schema, "62").migrate().migrationsExecuted);

            LegacyRows rows;
            try (Connection connection = connection(schema)) {
                rows = seedRepresentativeV62Rows(connection);
            }

            assertEquals(1, flyway(schema, "63").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("NOT_REQUIRED", remoteCloseState(connection, rows.localSessionId()));
                assertEquals("NOT_STARTED", remoteCloseState(connection, rows.openRemoteSessionId()));
                assertEquals("UNVERIFIED_LEGACY", remoteCloseState(connection, rows.closedRemoteSessionId()));
                for (long sessionId : new long[]{
                        rows.localSessionId(),
                        rows.openRemoteSessionId(),
                        rows.closedRemoteSessionId()}) {
                    assertEquals(0, queryLong(connection,
                            "SELECT remote_close_revision FROM work_session WHERE id = ?", sessionId));
                    assertNull(queryObject(connection,
                            "SELECT remote_close_operation_id FROM work_session WHERE id = ?", sessionId));
                    assertNull(queryObject(connection,
                            "SELECT remote_close_receipt_sha256 FROM work_session WHERE id = ?", sessionId));
                    assertNull(queryObject(connection,
                            "SELECT remote_close_error_code FROM work_session WHERE id = ?", sessionId));
                    assertNull(queryObject(connection,
                            "SELECT remote_close_requested_at FROM work_session WHERE id = ?", sessionId));
                    assertNull(queryObject(connection,
                            "SELECT remote_close_updated_at FROM work_session WHERE id = ?", sessionId));
                    assertNull(queryObject(connection,
                            "SELECT remote_close_released_at FROM work_session WHERE id = ?", sessionId));
                }
                assertNull(queryObject(connection,
                        "SELECT failure_code FROM agent_run WHERE id = ?", rows.agentRunId()));
                assertNull(queryObject(connection,
                        "SELECT recovery_next_action FROM agent_run WHERE id = ?", rows.agentRunId()));
                assertEquals("CLOSED", queryString(connection,
                        "SELECT status FROM work_session WHERE id = ?", rows.closedRemoteSessionId()));
            }
        });
    }

    @Test
    void rejectsOperationIdentityRevisionReceiptErrorAndRecoveryInconsistency() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "62").migrate();
            LegacyRows rows;
            try (Connection connection = connection(schema)) {
                rows = seedRepresentativeV62Rows(connection);
            }
            flyway(schema, "63").migrate();

            try (Connection connection = connection(schema)) {
                UUID operationId = UUID.randomUUID();
                execute(connection, """
                        UPDATE work_session
                        SET status = 'CLOSING',
                            remote_close_state = 'REQUESTED',
                            remote_close_operation_id = ?,
                            remote_close_revision = 1,
                            remote_close_requested_at = CURRENT_TIMESTAMP,
                            remote_close_updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, operationId, rows.openRemoteSessionId());

                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_operation_id = ?
                        WHERE id = ?
                        """, UUID.randomUUID(), rows.openRemoteSessionId());
                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_revision = 0
                        WHERE id = ?
                        """, rows.openRemoteSessionId());
                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_state = 'RELEASED',
                            remote_close_revision = 2,
                            remote_close_released_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, rows.openRemoteSessionId());
                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_state = 'BLOCKED',
                            remote_close_revision = 2
                        WHERE id = ?
                        """, rows.openRemoteSessionId());

                execute(connection, """
                        UPDATE work_session
                        SET remote_close_state = 'BLOCKED',
                            remote_close_revision = 2,
                            remote_close_error_code = 'REMOTE_OWNERSHIP_AMBIGUOUS',
                            remote_close_updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, rows.openRemoteSessionId());
                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_state = 'RELEASED',
                            remote_close_revision = 3,
                            remote_close_receipt_sha256 = ?,
                            remote_close_released_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, "a".repeat(64), rows.openRemoteSessionId());
                execute(connection, """
                        UPDATE work_session
                        SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP,
                            remote_close_state = 'RELEASED',
                            remote_close_revision = 3,
                            remote_close_error_code = NULL,
                            remote_close_receipt_sha256 = ?,
                            remote_close_updated_at = CURRENT_TIMESTAMP,
                            remote_close_released_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, "a".repeat(64), rows.openRemoteSessionId());

                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET status = 'CLOSING', closed_at = NULL,
                            remote_close_state = 'RECONCILING',
                            remote_close_revision = 4,
                            remote_close_receipt_sha256 = NULL,
                            remote_close_released_at = NULL,
                            remote_close_updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, rows.openRemoteSessionId());
                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_revision = 4,
                            remote_close_receipt_sha256 = ?,
                            remote_close_updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, "b".repeat(64), rows.openRemoteSessionId());
                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET remote_close_revision = 4,
                            remote_close_requested_at = remote_close_requested_at + INTERVAL '1 second',
                            remote_close_updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, rows.openRemoteSessionId());

                assertSqlRejected(connection, """
                        UPDATE agent_run
                        SET failure_code = 'CLOSED_SESSION_OWNS_CAPACITY'
                        WHERE id = ?
                        """, rows.agentRunId());
                assertSqlRejected(connection, """
                        UPDATE agent_run
                        SET failure_code = 'unsafe worker detail',
                            recovery_next_action = 'RECONCILE_REMOTE_CLOSE'
                        WHERE id = ?
                        """, rows.agentRunId());
                execute(connection, """
                        UPDATE agent_run
                        SET failure_code = 'CLOSED_SESSION_OWNS_CAPACITY',
                            recovery_next_action = 'RECONCILE_REMOTE_CLOSE'
                        WHERE id = ?
                        """, rows.agentRunId());
                assertEquals("CLOSED_SESSION_OWNS_CAPACITY", queryString(connection,
                        "SELECT failure_code FROM agent_run WHERE id = ?", rows.agentRunId()));
                assertEquals("RECONCILE_REMOTE_CLOSE", queryString(connection,
                        "SELECT recovery_next_action FROM agent_run WHERE id = ?", rows.agentRunId()));
            }
        });
    }

    private LegacyRows seedRepresentativeV62Rows(Connection connection) throws SQLException {
        long localProject = insertProject(connection, "V63 local project");
        long openRemoteProject = insertProject(connection, "V63 open remote project");
        long closedRemoteProject = insertProject(connection, "V63 closed remote project");
        execute(connection, """
                INSERT INTO worker_node (
                    id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, normal_in_use, heavy_in_use,
                    capabilities
                ) VALUES (
                    'ax42-v63-fixture', 'atenea-agent-run-worker/v1', 'http://worker.invalid',
                    TRUE, TRUE, 4, 2, 0, 0, 'project-codex-v1'
                )
                """);

        long localSession = queryLong(connection, """
                INSERT INTO work_session (
                    project_id, status, title, base_branch, execution_target,
                    workspace_identity, opened_at, last_activity_at, created_at, updated_at
                ) VALUES (
                    ?, 'OPEN', 'V63 local session', ?, 'LOCAL', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, localProject, BRANCH, "local:v63:" + UUID.randomUUID());
        UUID openRemoteId = UUID.randomUUID();
        long openRemoteSession = insertRemoteSession(
                connection, openRemoteProject, "OPEN", null, openRemoteId);
        UUID closedRemoteId = UUID.randomUUID();
        long closedRemoteSession = insertRemoteSession(
                connection, closedRemoteProject, "CLOSED", "CURRENT_TIMESTAMP", closedRemoteId);
        long turnId = queryLong(connection, """
                INSERT INTO session_turn (session_id, actor, message_text, internal, created_at)
                VALUES (?, 'OPERATOR', 'V63 content-free fixture', FALSE, CURRENT_TIMESTAMP)
                RETURNING id
                """, openRemoteSession);
        long runId = queryLong(connection, """
                INSERT INTO agent_run (
                    session_id, origin_turn_id, status, process_outcome,
                    target_repo_path, execution_target, selected_worker_id,
                    workspace_identity, dispatch_id, remote_session_id, workload_kind,
                    project_identity, repository_url, repository_branch,
                    repository_commit, manifest_sha256, workload_class,
                    started_at, finished_at, created_at
                ) VALUES (
                    ?, ?, 'FAILED', 'FAILED',
                    '/workspace/repos/internal/atenea', 'REMOTE', 'ax42-v63-fixture',
                    ?, ?, ?, 'project-codex-v1',
                    'atenea', 'https://github.com/jlnieto/atenea.git', ?,
                    ?, ?, 'NORMAL',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, openRemoteSession, turnId,
                workspaceIdentity(openRemoteId), UUID.randomUUID(), openRemoteId,
                BRANCH, "1".repeat(40), "2".repeat(64));
        return new LegacyRows(localSession, openRemoteSession, closedRemoteSession, runId);
    }

    private long insertRemoteSession(
            Connection connection,
            long projectId,
            String status,
            String closedAtExpression,
            UUID remoteSessionId
    ) throws SQLException {
        String sql = """
                INSERT INTO work_session (
                    project_id, status, title, base_branch, execution_target,
                    selected_worker_id, workspace_identity, remote_session_id,
                    remote_workload_kind, opened_at, last_activity_at, closed_at,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, 'V63 remote session', ?, 'REMOTE',
                    'ax42-v63-fixture', ?, ?, 'project-codex-v1',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, %s,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """.formatted(closedAtExpression == null ? "NULL" : closedAtExpression);
        return queryLong(connection, sql, projectId, status, BRANCH,
                workspaceIdentity(remoteSessionId), remoteSessionId);
    }

    private String workspaceIdentity(UUID remoteSessionId) {
        return "remote:ax42-v63-fixture:work-session:" + remoteSessionId;
    }

    private long insertProject(Connection connection, String name) throws SQLException {
        return queryLong(connection, """
                INSERT INTO project (name, repo_path, default_base_branch)
                VALUES (?, '/workspace/repos/internal/atenea', ?)
                RETURNING id
                """, name, BRANCH);
    }

    private String remoteCloseState(Connection connection, long sessionId) throws SQLException {
        return queryString(connection,
                "SELECT remote_close_state FROM work_session WHERE id = ?", sessionId);
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
        connection.setSchema(schema);
        return connection;
    }

    private void withIsolatedSchema(CheckedSchemaConsumer test) throws Exception {
        String schema = "v63_remote_close_" + UUID.randomUUID().toString().replace("-", "");
        try {
            test.accept(schema);
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

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        return queryLong(connection, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, table, column) == 1;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return queryLong(connection, """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = ?
                """, table) == 1;
    }

    private boolean indexExists(Connection connection, String index) throws SQLException {
        return queryLong(connection, """
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index) == 1;
    }

    private void assertSqlRejected(Connection connection, String sql, Object... parameters) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(connection, sql, parameters));
        assertTrue(exception.getSQLState() != null && exception.getSQLState().startsWith("23"),
                "Expected an integrity rejection but received SQLSTATE " + exception.getSQLState());
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

    private String queryString(Connection connection, String sql, Object... parameters) throws SQLException {
        return String.valueOf(queryObject(connection, sql, parameters));
    }

    private Object queryObject(Connection connection, String sql, Object... parameters) throws SQLException {
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

    private record LegacyRows(
            long localSessionId,
            long openRemoteSessionId,
            long closedRemoteSessionId,
            long agentRunId
    ) {
    }

    @FunctionalInterface
    private interface CheckedSchemaConsumer {
        void accept(String schema) throws Exception;
    }
}
