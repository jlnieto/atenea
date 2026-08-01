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

class V62RealAttachmentMigrationTest {

    private static final String ATENEA_BRANCH = "feature/actualizar-conversacion-en-web";

    @Test
    void migratesEmptySchemaToV62AndSecondMigrationIsNoOp() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "62");

            assertEquals(62, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("62", queryString(connection, """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = TRUE
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """));
                assertTrue(relationExists(connection, "session_turn_attachment"));
                for (String column : new String[]{
                        "work_session.attachment_policy_revision",
                        "session_turn.client_request_id",
                        "session_turn.request_fingerprint_sha256",
                        "work_session_attachment.storage_scope",
                        "work_session_attachment.remote_session_id",
                        "work_session_attachment.workspace_identity",
                        "agent_run.attachment_count",
                        "agent_run.attachment_bytes",
                        "agent_run.attachment_manifest_sha256"}) {
                    String[] identity = column.split("\\.", 2);
                    assertTrue(columnExists(connection, identity[0], identity[1]), column);
                }
            }
        });
    }

    @Test
    void migratesRepresentativeV61RowsWithoutRetroactiveEligibilityOrOwnership() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flywayV61 = flyway(schema, "61");
            assertEquals(61, flywayV61.migrate().migrationsExecuted);

            LegacyOwnership legacy;
            try (Connection connection = connection(schema)) {
                legacy = seedLegacyRemoteOwnership(connection);
            }

            assertEquals(1, flyway(schema, "62").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertNull(queryObject(connection,
                        "SELECT attachment_policy_revision FROM work_session WHERE id = ?",
                        legacy.sessionId()));
                assertNull(queryObject(connection,
                        "SELECT client_request_id FROM session_turn WHERE id = ?",
                        legacy.turnId()));
                assertNull(queryObject(connection,
                        "SELECT request_fingerprint_sha256 FROM session_turn WHERE id = ?",
                        legacy.turnId()));
                assertNull(queryObject(connection,
                        "SELECT storage_scope FROM work_session_attachment WHERE id = ?",
                        legacy.attachmentId()));
                assertNull(queryObject(connection,
                        "SELECT remote_session_id FROM work_session_attachment WHERE id = ?",
                        legacy.attachmentId()));
                assertNull(queryObject(connection,
                        "SELECT workspace_identity FROM work_session_attachment WHERE id = ?",
                        legacy.attachmentId()));
                assertEquals(0, queryLong(connection,
                        "SELECT attachment_count FROM agent_run WHERE id = ?",
                        legacy.runId()));
                assertEquals(0, queryLong(connection,
                        "SELECT attachment_bytes FROM agent_run WHERE id = ?",
                        legacy.runId()));
                assertNull(queryObject(connection,
                        "SELECT attachment_manifest_sha256 FROM agent_run WHERE id = ?",
                        legacy.runId()));
                assertEquals(0, queryLong(connection, "SELECT count(*) FROM session_turn_attachment"));
            }
        });
    }

    @Test
    void rejectsPartialForeignAndAmbiguousV62Ownership() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "61").migrate();
            LegacyOwnership owner;
            try (Connection connection = connection(schema)) {
                owner = seedLegacyRemoteOwnership(connection);
            }
            flyway(schema, "62").migrate();

            try (Connection connection = connection(schema)) {
                long foreignProjectId = insertProject(connection, "Foreign V62 project");
                long localSessionId = insertLocalSession(connection, foreignProjectId);
                long localTurnId = insertTurn(connection, localSessionId, null, null);

                assertSqlRejected(connection, """
                        UPDATE work_session
                        SET attachment_policy_revision = 'atenea-real-attachments-v1'
                        WHERE id = ?
                        """, localSessionId);

                assertSqlRejected(connection, """
                        INSERT INTO session_turn (
                            session_id, actor, message_text, internal,
                            client_request_id, created_at
                        ) VALUES (?, 'OPERATOR', 'partial request', FALSE, ?, CURRENT_TIMESTAMP)
                        """, owner.sessionId(), UUID.randomUUID());

                assertSqlRejected(connection, """
                        INSERT INTO work_session_attachment (
                            id, work_session_id, project_id, source, kind,
                            original_filename, content_type, size_bytes,
                            retention_class, retain_until, sha256, worker_id,
                            storage_identity, storage_scope, remote_session_id,
                            created_at, indexed_at
                        ) VALUES (
                            ?, ?, ?, 'OPERATOR_UPLOAD', 'IMAGE',
                            'partial.png', 'image/png', 128,
                            'SESSION', CURRENT_TIMESTAMP + INTERVAL '1 day', ?, 'ax42-01',
                            ?, 'REAL_SESSION', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        """,
                        UUID.randomUUID(), owner.sessionId(), owner.projectId(), "d".repeat(64),
                        "partial:" + UUID.randomUUID(), owner.remoteSessionId());

                assertSqlRejected(connection, """
                        INSERT INTO work_session_attachment (
                            id, work_session_id, project_id, source, kind,
                            original_filename, content_type, size_bytes,
                            retention_class, retain_until, sha256, worker_id,
                            storage_identity, created_at, indexed_at
                        ) VALUES (
                            ?, ?, ?, 'OPERATOR_UPLOAD', 'IMAGE',
                            'foreign-project.png', 'image/png', 128,
                            'SESSION', CURRENT_TIMESTAMP + INTERVAL '1 day', ?, 'ax42-01',
                            ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                        )
                        """,
                        UUID.randomUUID(), owner.sessionId(), foreignProjectId, "e".repeat(64),
                        "foreign-project:" + UUID.randomUUID());

                assertSqlRejected(connection, """
                        INSERT INTO session_turn_attachment (
                            work_session_id, session_turn_id, attachment_id, position
                        ) VALUES (?, ?, ?, 0)
                        """, localSessionId, localTurnId, owner.attachmentId());

                assertSqlRejected(connection, """
                        UPDATE agent_run
                        SET attachment_count = 1, attachment_bytes = 128
                        WHERE id = ?
                        """, owner.runId());

                UUID requestId = UUID.randomUUID();
                long requestTurnId = insertTurn(
                        connection, owner.sessionId(), requestId, "a".repeat(64));
                assertSqlRejected(connection, """
                        INSERT INTO session_turn (
                            session_id, actor, message_text, internal,
                            client_request_id, request_fingerprint_sha256, created_at
                        ) VALUES (?, 'OPERATOR', 'conflicting replay', FALSE, ?, ?, CURRENT_TIMESTAMP)
                        """, owner.sessionId(), requestId, "b".repeat(64));

                UUID firstAttachment = insertRealAttachment(connection, owner, "first");
                UUID secondAttachment = insertRealAttachment(connection, owner, "second");
                execute(connection, """
                        INSERT INTO session_turn_attachment (
                            work_session_id, session_turn_id, attachment_id, position
                        ) VALUES (?, ?, ?, 0)
                        """, owner.sessionId(), requestTurnId, firstAttachment);
                assertSqlRejected(connection, """
                        INSERT INTO session_turn_attachment (
                            work_session_id, session_turn_id, attachment_id, position
                        ) VALUES (?, ?, ?, 0)
                        """, owner.sessionId(), requestTurnId, secondAttachment);

                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM session_turn_attachment WHERE session_turn_id = ?",
                        requestTurnId));
                assertEquals(3, queryLong(connection, "SELECT count(*) FROM session_turn"));
                assertEquals(3, queryLong(connection, "SELECT count(*) FROM work_session_attachment"));
                assertEquals(0, queryLong(connection,
                        "SELECT attachment_count FROM agent_run WHERE id = ?",
                        owner.runId()));
                assertEquals(0, queryLong(connection,
                        "SELECT attachment_bytes FROM agent_run WHERE id = ?",
                        owner.runId()));
                assertNull(queryObject(connection,
                        "SELECT attachment_manifest_sha256 FROM agent_run WHERE id = ?",
                        owner.runId()));
            }
        });
    }

    private LegacyOwnership seedLegacyRemoteOwnership(Connection connection) throws SQLException {
        long projectId = insertProject(connection, "Legacy V61 Atenea");
        execute(connection, """
                INSERT INTO worker_node (
                    id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, normal_in_use, heavy_in_use,
                    capabilities
                ) VALUES (
                    'ax42-01', 'atenea-agent-run-worker/v1', 'http://worker.invalid',
                    TRUE, TRUE, 4, 2, 0, 0, 'project-codex-v1'
                )
                """);

        UUID remoteSessionId = UUID.randomUUID();
        String workspaceIdentity = "remote:ax42-01:work-session:" + remoteSessionId;
        long sessionId = queryLong(connection, """
                INSERT INTO work_session (
                    project_id, status, title, base_branch, execution_target,
                    selected_worker_id, workspace_identity, remote_session_id,
                    remote_workload_kind, opened_at, last_activity_at, created_at, updated_at
                ) VALUES (
                    ?, 'OPEN', 'Legacy V61 session', ?, 'REMOTE',
                    'ax42-01', ?, ?, 'project-codex-v1',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, projectId, ATENEA_BRANCH, workspaceIdentity, remoteSessionId);
        long turnId = queryLong(connection, """
                INSERT INTO session_turn (
                    session_id, actor, message_text, internal, created_at
                ) VALUES (?, 'OPERATOR', 'legacy migration fixture', FALSE, CURRENT_TIMESTAMP)
                RETURNING id
                """, sessionId);
        long runId = queryLong(connection, """
                INSERT INTO agent_run (
                    session_id, origin_turn_id, status, process_outcome,
                    target_repo_path, execution_target, selected_worker_id,
                    workspace_identity, dispatch_id, remote_session_id, workload_kind,
                    project_identity, repository_url, repository_branch,
                    repository_commit, manifest_sha256, workload_class,
                    started_at, finished_at, created_at
                ) VALUES (
                    ?, ?, 'SUCCEEDED', 'SUCCEEDED',
                    '/workspace/repos/internal/atenea', 'REMOTE', 'ax42-01',
                    ?, ?, ?, 'project-codex-v1',
                    'atenea', 'https://github.com/jlnieto/atenea.git', ?,
                    ?, ?, 'NORMAL',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """,
                sessionId, turnId, workspaceIdentity, UUID.randomUUID(), remoteSessionId,
                ATENEA_BRANCH, "1".repeat(40), "2".repeat(64));

        UUID attachmentId = UUID.randomUUID();
        execute(connection, """
                INSERT INTO work_session_attachment (
                    id, work_session_id, project_id, source, kind,
                    original_filename, content_type, size_bytes, retention_class,
                    retain_until, sha256, worker_id, storage_identity,
                    created_at, indexed_at
                ) VALUES (
                    ?, ?, ?, 'OPERATOR_UPLOAD', 'IMAGE',
                    'legacy.png', 'image/png', 128, 'SESSION',
                    CURRENT_TIMESTAMP + INTERVAL '1 day', ?, 'ax42-01', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """,
                attachmentId, sessionId, projectId, "3".repeat(64),
                "legacy:" + attachmentId);
        return new LegacyOwnership(
                projectId, sessionId, turnId, runId, attachmentId,
                remoteSessionId, workspaceIdentity);
    }

    private long insertProject(Connection connection, String name) throws SQLException {
        return queryLong(connection, """
                INSERT INTO project (name, repo_path, default_base_branch)
                VALUES (?, '/workspace/repos/internal/atenea', ?)
                RETURNING id
                """, name, ATENEA_BRANCH);
    }

    private long insertLocalSession(Connection connection, long projectId) throws SQLException {
        return queryLong(connection, """
                INSERT INTO work_session (
                    project_id, status, title, base_branch, execution_target,
                    workspace_identity, opened_at, last_activity_at, created_at, updated_at
                ) VALUES (
                    ?, 'OPEN', 'Foreign local session', ?, 'LOCAL',
                    ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                ) RETURNING id
                """, projectId, ATENEA_BRANCH, "local:test:" + UUID.randomUUID());
    }

    private long insertTurn(
            Connection connection,
            long sessionId,
            UUID clientRequestId,
            String fingerprint
    ) throws SQLException {
        return queryLong(connection, """
                INSERT INTO session_turn (
                    session_id, actor, message_text, internal,
                    client_request_id, request_fingerprint_sha256, created_at
                ) VALUES (?, 'OPERATOR', 'migration fixture', FALSE, ?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, sessionId, clientRequestId, fingerprint);
    }

    private UUID insertRealAttachment(
            Connection connection,
            LegacyOwnership owner,
            String identitySuffix
    ) throws SQLException {
        UUID attachmentId = UUID.randomUUID();
        execute(connection, """
                INSERT INTO work_session_attachment (
                    id, work_session_id, project_id, source, kind,
                    original_filename, content_type, size_bytes,
                    retention_class, retain_until, sha256, worker_id,
                    storage_identity, storage_scope, remote_session_id,
                    workspace_identity, created_at, indexed_at
                ) VALUES (
                    ?, ?, ?, 'OPERATOR_UPLOAD', 'IMAGE',
                    ?, 'image/png', 128,
                    'SESSION', CURRENT_TIMESTAMP + INTERVAL '1 day', ?, 'ax42-01',
                    ?, 'REAL_SESSION', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """,
                attachmentId, owner.sessionId(), owner.projectId(), identitySuffix + ".png",
                "4".repeat(64), "real:" + identitySuffix + ":" + attachmentId,
                owner.remoteSessionId(), owner.workspaceIdentity());
        return attachmentId;
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
        String schema = "v62_attachments_" + UUID.randomUUID().toString().replace("-", "");
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

    private boolean relationExists(Connection connection, String relation) throws SQLException {
        return Boolean.TRUE.equals(queryObject(connection,
                "SELECT to_regclass(?) IS NOT NULL", relation));
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        return queryLong(connection, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, table, column) == 1;
    }

    private void assertSqlRejected(Connection connection, String sql, Object... parameters) {
        SQLException exception = assertThrows(
                SQLException.class,
                () -> execute(connection, sql, parameters));
        assertTrue(exception.getSQLState() != null && exception.getSQLState().startsWith("23"),
                "Expected an integrity constraint rejection but received SQLSTATE " + exception.getSQLState());
    }

    private void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private long queryLong(Connection connection, String sql, Object... parameters) throws SQLException {
        Object value = queryObject(connection, sql, parameters);
        return ((Number) value).longValue();
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

    private record LegacyOwnership(
            long projectId,
            long sessionId,
            long turnId,
            long runId,
            UUID attachmentId,
            UUID remoteSessionId,
            String workspaceIdentity
    ) {
    }

    @FunctionalInterface
    private interface CheckedSchemaConsumer {
        void accept(String schema) throws Exception;
    }
}
