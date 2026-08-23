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

class V69OperatorWebAuthnMigrationTest {

    @Test
    void migratesEmptySchemaToV69WithDurableFailClosedStructures() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "69");
            assertEquals(69, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);

            try (Connection connection = connection(schema)) {
                assertEquals("69", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
                assertTrue(tableExists(connection, "operator_webauthn_user"));
                assertTrue(tableExists(connection, "operator_webauthn_credential"));
                assertTrue(tableExists(connection, "operator_webauthn_challenge"));
                assertTrue(columnExists(connection, "operator_session_family", "authenticated_at"));
                assertTrue(columnExists(connection, "operator_session_family", "authentication_method"));
                assertTrue(indexExists(connection, "idx_operator_webauthn_credential_active"));
                assertTrue(indexExists(connection, "idx_operator_webauthn_challenge_live"));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM operator_webauthn_user"));
            }
        });
    }

    @Test
    void upgradeFromV68PreservesLegacyAccountsFamiliesAndTokens() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "68").migrate();
            String before;
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v69-legacy@atenea.test");
                UUID familyId = UUID.randomUUID();
                execute(connection, """
                        INSERT INTO operator_session_family (
                            id, operator_id, client_type, device_label,
                            current_generation, created_at, last_used_at,
                            absolute_expires_at)
                        VALUES (?, ?, 'WEB', 'Legacy browser', 0,
                            '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z',
                            '2026-09-12T10:00:00Z')
                        """, familyId, operatorId);
                execute(connection, """
                        INSERT INTO operator_refresh_token (
                            operator_id, token_hash, expires_at, created_at, updated_at,
                            session_family_id, generation)
                        VALUES (?, ?, '2026-09-12T10:00:00Z',
                            '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z', ?, 0)
                        """, operatorId, "a".repeat(64), familyId);
                before = queryString(connection, """
                        SELECT ROW(a.id, a.email, a.credential_version, a.role_version,
                            f.id, f.current_generation, r.token_hash, r.generation)::text
                        FROM operator_account a
                        JOIN operator_session_family f ON f.operator_id = a.id
                        JOIN operator_refresh_token r ON r.session_family_id = f.id
                        WHERE a.id = ?
                        """, operatorId);
            }

            assertEquals(1, flyway(schema, "69").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                long operatorId = queryLong(connection,
                        "SELECT id FROM operator_account WHERE email = ?",
                        "v69-legacy@atenea.test");
                assertEquals(before, queryString(connection, """
                        SELECT ROW(a.id, a.email, a.credential_version, a.role_version,
                            f.id, f.current_generation, r.token_hash, r.generation)::text
                        FROM operator_account a
                        JOIN operator_session_family f ON f.operator_id = a.id
                        JOIN operator_refresh_token r ON r.session_family_id = f.id
                        WHERE a.id = ?
                        """, operatorId));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM operator_webauthn_credential"));
                assertEquals(1, queryLong(connection, """
                        SELECT count(*) FROM operator_session_family
                        WHERE authenticated_at IS NULL AND authentication_method IS NULL
                        """));
            }
        });
    }

    @Test
    void constraintsRejectRawOrAmbiguousWebAuthnState() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "69").migrate();
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v69-contract@atenea.test");
                UUID familyId = UUID.randomUUID();
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
                assertSqlRejected(connection, """
                        INSERT INTO operator_webauthn_user (
                            operator_id, user_handle, created_at, updated_at)
                        VALUES (?, ?, now(), now())
                        """, operatorId, new byte[31]);
                assertSqlRejected(connection, credentialSql(), UUID.randomUUID(), operatorId,
                        new byte[16], new byte[16], -999, UUID.randomUUID(), false, true);
                assertSqlRejected(connection, challengeSql(), UUID.randomUUID(), new byte[31],
                        "AUTHENTICATION", "WEB", null, null,
                        "atenea.yudri.es", "https://atenea.yudri.es");
                assertSqlRejected(connection, challengeSql(), UUID.randomUUID(), new byte[32],
                        "REGISTRATION", "WEB", null, null,
                        "atenea.yudri.es", "https://atenea.yudri.es");
                assertSqlRejected(connection, challengeSql(), UUID.randomUUID(), new byte[32],
                        "AUTHENTICATION", "WEB", operatorId, familyId,
                        "atenea.yudri.es", "https://atenea.yudri.es");
            }
        });
    }

    private String credentialSql() {
        return """
                INSERT INTO operator_webauthn_credential (
                    id, operator_id, credential_id, public_key_cose, algorithm,
                    aaguid, sign_count, transports, backup_eligible, backup_state,
                    created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, '', ?, ?, now())
                """;
    }

    private String challengeSql() {
        return """
                INSERT INTO operator_webauthn_challenge (
                    id, challenge_digest, purpose, channel, operator_id,
                    session_family_id, relying_party_id, expected_origin,
                    created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now() + interval '5 minutes')
                """;
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v69_" + UUID.randomUUID().toString().replace("-", "");
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
                    email, display_name, password_hash, active, created_at, updated_at)
                VALUES (?, 'Synthetic V69 operator', 'synthetic-hash', TRUE,
                    '2026-08-12T10:00:00Z', '2026-08-12T10:00:00Z')
                RETURNING id
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
        try {
            assertFalse(connection.isClosed());
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
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
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(String schema) throws Exception;
    }
}
