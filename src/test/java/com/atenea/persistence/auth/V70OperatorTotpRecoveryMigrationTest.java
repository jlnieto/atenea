package com.atenea.persistence.auth;

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

class V70OperatorTotpRecoveryMigrationTest {
    @Test
    void migratesEmptySchemaToV70WithExpandOnlyRecoveryStructures() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "70");
            assertEquals(70, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                assertEquals("70", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
                assertTrue(columnExists(connection, "operator_account",
                        "factor_reenrollment_required"));
                assertTrue(tableExists(connection, "operator_totp_factor"));
                assertTrue(tableExists(connection, "operator_recovery_code"));
                assertTrue(tableExists(connection, "operator_auth_attempt_window"));
                assertTrue(tableExists(connection, "operator_security_event"));
                assertTrue(tableExists(connection, "operator_security_notification"));
                assertTrue(indexExists(connection, "uk_operator_totp_factor_active"));
                assertTrue(indexExists(connection, "idx_operator_recovery_code_active"));
            }
        });
    }

    @Test
    void upgradeFromV69PreservesAccountsSessionsAndWebAuthn() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "69").migrate();
            long operatorId;
            UUID familyId = UUID.randomUUID();
            try (Connection connection = connection(schema)) {
                operatorId = insertOperator(connection, "v70-legacy@atenea.test");
                execute(connection, """
                        INSERT INTO operator_session_family (
                            id, operator_id, client_type, device_label,
                            current_generation, created_at, last_used_at,
                            absolute_expires_at, authenticated_at, authentication_method)
                        VALUES (?, ?, 'WEB', 'Synthetic browser', 0,
                            '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z',
                            '2026-09-12T10:00:00Z', '2026-08-12T10:00:00Z', 'pwd')
                        """, familyId, operatorId);
                execute(connection, """
                        INSERT INTO operator_webauthn_user (
                            operator_id, user_handle, created_at, updated_at)
                        VALUES (?, ?, '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z')
                        """, operatorId, new byte[32]);
            }
            assertEquals(1, flyway(schema, "70").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM operator_account WHERE id = ?", operatorId));
                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM operator_session_family WHERE id = ?", familyId));
                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM operator_webauthn_user WHERE operator_id = ?",
                        operatorId));
                assertEquals(0, queryLong(connection, """
                        SELECT count(*) FROM operator_account
                        WHERE id = ? AND factor_reenrollment_required
                        """, operatorId));
            }
        });
    }

    @Test
    void constraintsRejectPlainOrAmbiguousRecoveryState() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "70").migrate();
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v70-contract@atenea.test");
                UUID factorId = UUID.randomUUID();
                execute(connection, factorSql(), factorId, operatorId, UUID.randomUUID(),
                        new byte[48], "v1", "ACTIVE",
                        "2026-08-12T10:00:00Z", "2026-08-12T10:10:00Z",
                        "2026-08-12T10:01:00Z", null, null);
                assertSqlRejected(connection, factorSql(), UUID.randomUUID(), operatorId,
                        UUID.randomUUID(), "plain-secret".getBytes(), "v1", "PENDING",
                        "2026-08-12T10:00:00Z", "2026-08-12T10:10:00Z",
                        null, null, null);
                assertSqlRejected(connection, factorSql(), UUID.randomUUID(), operatorId,
                        UUID.randomUUID(), new byte[48], "V 1", "PENDING",
                        "2026-08-12T10:00:00Z", "2026-08-12T10:10:00Z",
                        null, null, null);
                assertSqlRejected(connection, """
                        INSERT INTO operator_recovery_code (
                            id, operator_id, factor_id, batch_id, code_hmac,
                            hmac_key_version, created_at, consumed_at, revoked_at,
                            revocation_reason)
                        VALUES (?, ?, ?, ?, ?, 'v1', now(), now(), now(), 'INVALID')
                        """, UUID.randomUUID(), operatorId, factorId, UUID.randomUUID(),
                        new byte[32]);
                assertSqlRejected(connection, """
                        INSERT INTO operator_auth_attempt_window (
                            id, operator_id, scope, window_started_at, failed_count, updated_at)
                        VALUES (?, ?, 'GENERAL_LOGIN', now(), 1, now())
                        """, UUID.randomUUID(), operatorId);
            }
        });
    }

    private String factorSql() {
        return """
                INSERT INTO operator_totp_factor (
                    id, operator_id, enrollment_id, encrypted_secret,
                    secret_key_version, state, created_at, expires_at,
                    activated_at, revoked_at, revocation_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz,
                    ?::timestamptz, ?::timestamptz, ?)
                """;
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v70_" + UUID.randomUUID().toString().replace("-", "");
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
                .schemas(schema).defaultSchema(schema).createSchemas(true)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target)).load();
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
                    email, display_name, password_hash, active, created_at, updated_at)
                VALUES (?, 'Synthetic V70 operator', 'synthetic-hash', TRUE,
                    '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z') RETURNING id
                """, email);
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
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, table, column) == 1;
    }

    private boolean indexExists(Connection connection, String index) throws SQLException {
        return queryLong(connection, """
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema() AND indexname = ?
                """, index) == 1;
    }

    private void assertSqlRejected(Connection connection, String sql, Object... values) {
        assertThrows(SQLException.class, () -> execute(connection, sql, values));
    }

    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private long queryLong(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private String queryString(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    @FunctionalInterface
    private interface SqlWork { void run(String schema) throws Exception; }
}
