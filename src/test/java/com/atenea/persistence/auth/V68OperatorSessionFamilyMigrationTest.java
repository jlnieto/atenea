package com.atenea.persistence.auth;

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

class V68OperatorSessionFamilyMigrationTest {

    @Test
    void migratesEmptySchemaToV68WithoutCreatingSessionFamilies() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "68");

            assertEquals(68, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("68", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
                assertTrue(tableExists(connection, "operator_session_family"));
                assertTrue(columnExists(connection, "operator_account", "credential_version"));
                assertTrue(columnExists(connection, "operator_account", "role_version"));
                for (String column : new String[]{
                        "session_family_id",
                        "generation",
                        "consumed_at",
                        "replaced_by_token_id",
                        "revocation_reason"}) {
                    assertTrue(columnExists(connection, "operator_refresh_token", column), column);
                }
                assertTrue(indexExists(connection,
                        "idx_operator_session_family_active_inventory"));
                assertTrue(indexExists(connection,
                        "idx_operator_refresh_token_active_family"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM operator_session_family"));
            }
        });
    }

    @Test
    void upgradeFromV67PreservesLegacyRefreshRowsWithoutInventedBackfill()
            throws Exception {
        withIsolatedSchema(schema -> {
            assertEquals(67, flyway(schema, "67").migrate().migrationsExecuted);
            long operatorId;
            String operatorBefore;
            String refreshBefore;
            try (Connection connection = connection(schema)) {
                operatorId = insertOperator(connection, "v68-legacy@atenea.test");
                insertLegacyRefresh(connection, operatorId, "a".repeat(64),
                        "2026-09-01T00:00:00Z", null);
                insertLegacyRefresh(connection, operatorId, "b".repeat(64),
                        "2026-09-02T00:00:00Z", "2026-08-10T00:00:00Z");
                insertLegacyRefresh(connection, operatorId, "c".repeat(64),
                        "2026-08-01T00:00:00Z", null);
                operatorBefore = legacyOperatorProjection(connection, operatorId);
                refreshBefore = legacyRefreshProjection(connection, operatorId);
            }

            assertEquals(1, flyway(schema, "68").migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals(operatorBefore, legacyOperatorProjection(connection, operatorId));
                assertEquals(refreshBefore, legacyRefreshProjection(connection, operatorId));
                assertEquals(0, queryLong(connection,
                        "SELECT credential_version FROM operator_account WHERE id = ?",
                        operatorId));
                assertEquals(0, queryLong(connection,
                        "SELECT role_version FROM operator_account WHERE id = ?",
                        operatorId));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM operator_session_family"));
                assertEquals(3, queryLong(connection, """
                        SELECT count(*) FROM operator_refresh_token
                        WHERE operator_id = ?
                          AND session_family_id IS NULL
                          AND generation IS NULL
                          AND consumed_at IS NULL
                          AND replaced_by_token_id IS NULL
                          AND revocation_reason IS NULL
                        """, operatorId));
            }
        });
    }

    @Test
    void constraintsKeepVersionsFamiliesRotationAndRevocationFailClosed()
            throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "68").migrate();
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v68-contract@atenea.test");
                assertSqlRejected(connection,
                        "UPDATE operator_account SET credential_version = -1 WHERE id = ?",
                        operatorId);
                assertSqlRejected(connection,
                        "UPDATE operator_account SET role_version = -1 WHERE id = ?",
                        operatorId);

                UUID familyOne = UUID.randomUUID();
                UUID familyTwo = UUID.randomUUID();
                insertFamily(connection, familyOne, operatorId, "WEB", "Work browser", 0);
                insertFamily(connection, familyTwo, operatorId, "ANDROID", "Operator phone", 0);

                assertSqlRejected(connection, familyInsertSql(), UUID.randomUUID(), operatorId,
                        "web", "Lowercase type", 0);
                assertSqlRejected(connection, familyInsertSql(), UUID.randomUUID(), operatorId,
                        "WEB", " unsafe label ", 0);
                assertSqlRejected(connection, familyInsertSql(), UUID.randomUUID(), operatorId,
                        "WEB", "Negative generation", -1);
                assertSqlRejected(connection, """
                        UPDATE operator_session_family
                        SET revoked_at = '2026-08-12T10:05:00Z'
                        WHERE id = ?
                        """, familyOne);

                long first = insertFamilyRefresh(
                        connection, operatorId, familyOne, 0, "d".repeat(64));
                long successor = insertFamilyRefresh(
                        connection, operatorId, familyOne, 1, "e".repeat(64));
                long otherFamily = insertFamilyRefresh(
                        connection, operatorId, familyTwo, 0, "f".repeat(64));

                assertSqlRejected(connection, familyRefreshInsertSql(), operatorId,
                        "0".repeat(64), familyOne, 1);
                execute(connection, """
                        UPDATE operator_refresh_token
                        SET consumed_at = '2026-08-12T10:01:00Z',
                            revoked_at = '2026-08-12T10:01:00Z',
                            replaced_by_token_id = ?,
                            revocation_reason = 'ROTATED',
                            updated_at = '2026-08-12T10:01:00Z'
                        WHERE id = ?
                        """, successor, first);
                assertEquals(successor, queryLong(connection, """
                        SELECT replaced_by_token_id FROM operator_refresh_token WHERE id = ?
                        """, first));

                long crossFamilySource = insertFamilyRefresh(
                        connection, operatorId, familyOne, 2, "1".repeat(64));
                assertSqlRejected(connection, """
                        UPDATE operator_refresh_token
                        SET consumed_at = '2026-08-12T10:02:00Z',
                            revoked_at = '2026-08-12T10:02:00Z',
                            replaced_by_token_id = ?,
                            revocation_reason = 'ROTATED',
                            updated_at = '2026-08-12T10:02:00Z'
                        WHERE id = ?
                        """, otherFamily, crossFamilySource);
                assertSqlRejected(connection, """
                        UPDATE operator_refresh_token
                        SET consumed_at = '2026-08-12T10:02:00Z',
                            revoked_at = '2026-08-12T10:02:00Z',
                            replaced_by_token_id = id,
                            revocation_reason = 'ROTATED',
                            updated_at = '2026-08-12T10:02:00Z'
                        WHERE id = ?
                        """, successor);
                assertSqlRejected(connection, """
                        UPDATE operator_refresh_token
                        SET revoked_at = '2026-08-12T10:02:00Z',
                            revocation_reason = 'not-stable',
                            updated_at = '2026-08-12T10:02:00Z'
                        WHERE id = ?
                        """, otherFamily);
            }
        });
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v68_" + UUID.randomUUID().toString().replace("-", "");
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

    private long insertOperator(Connection connection, String email) throws SQLException {
        return queryLong(connection, """
                INSERT INTO operator_account (
                    email, display_name, password_hash, active,
                    created_at, updated_at)
                VALUES (?, 'Synthetic V68 operator', 'synthetic-hash', TRUE,
                    '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z')
                RETURNING id
                """, email);
    }

    private void insertLegacyRefresh(
            Connection connection,
            long operatorId,
            String tokenHash,
            String expiresAt,
            String revokedAt
    ) throws SQLException {
        execute(connection, """
                INSERT INTO operator_refresh_token (
                    operator_id, token_hash, expires_at, revoked_at,
                    created_at, updated_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz,
                    '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')
                """, operatorId, tokenHash, expiresAt, revokedAt);
    }

    private void insertFamily(
            Connection connection,
            UUID familyId,
            long operatorId,
            String clientType,
            String deviceLabel,
            long generation
    ) throws SQLException {
        execute(connection, familyInsertSql(),
                familyId, operatorId, clientType, deviceLabel, generation);
    }

    private String familyInsertSql() {
        return """
                INSERT INTO operator_session_family (
                    id, operator_id, client_type, device_label,
                    current_generation, created_at, last_used_at,
                    absolute_expires_at)
                VALUES (?, ?, ?, ?, ?, '2026-08-12T10:00:00Z',
                    '2026-08-12T10:00:00Z', '2026-09-12T10:00:00Z')
                """;
    }

    private long insertFamilyRefresh(
            Connection connection,
            long operatorId,
            UUID familyId,
            long generation,
            String tokenHash
    ) throws SQLException {
        return queryLong(connection, familyRefreshInsertSql(),
                operatorId, tokenHash, familyId, generation);
    }

    private String familyRefreshInsertSql() {
        return """
                INSERT INTO operator_refresh_token (
                    operator_id, token_hash, expires_at, created_at, updated_at,
                    session_family_id, generation)
                VALUES (?, ?, '2026-09-12T10:00:00Z',
                    '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z', ?, ?)
                RETURNING id
                """;
    }

    private String legacyOperatorProjection(Connection connection, long operatorId)
            throws SQLException {
        return queryString(connection, """
                SELECT ROW(id, email, display_name, password_hash, active,
                    codex_operations_role, created_at, updated_at)::text
                FROM operator_account WHERE id = ?
                """, operatorId);
    }

    private String legacyRefreshProjection(Connection connection, long operatorId)
            throws SQLException {
        return queryString(connection, """
                SELECT string_agg(ROW(id, operator_id, token_hash, expires_at,
                    revoked_at, last_used_at, created_at, updated_at)::text,
                    '|' ORDER BY id)
                FROM operator_refresh_token WHERE operator_id = ?
                """, operatorId);
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

    private boolean indexExists(Connection connection, String index) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index) == 1;
    }

    private void assertSqlRejected(Connection connection, String sql, Object... parameters) {
        SQLException failure = assertThrows(SQLException.class,
                () -> execute(connection, sql, parameters));
        assertTrue(failure.getSQLState() != null && failure.getSQLState().startsWith("23"),
                "Expected integrity rejection but received SQLSTATE " + failure.getSQLState());
    }

    private void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
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
            throw new IllegalStateException("Missing required test environment: " + name);
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(String schema) throws Exception;
    }
}
