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

class V71PrivilegedActionAuthorizationMigrationTest {
    @Test
    void migratesEmptySchemaToV71WithExpandOnlyActionAuthorization() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "71");
            assertEquals(71, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                assertEquals("71", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
                assertTrue(tableExists(connection,
                        "operator_privileged_action_authorization"));
                assertTrue(columnExists(connection, "operator_webauthn_challenge",
                        "target_fingerprint"));
                assertTrue(indexExists(connection,
                        "idx_operator_action_authorization_live"));
            }
        });
    }

    @Test
    void upgradeFromV70PreservesExistingAuthenticationState() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "70").migrate();
            UUID familyId = UUID.randomUUID();
            long operatorId;
            try (Connection connection = connection(schema)) {
                operatorId = insertOperator(connection, "v71-upgrade@atenea.test");
                insertFamily(connection, familyId, operatorId);
            }
            assertEquals(1, flyway(schema, "71").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM operator_account WHERE id = ?", operatorId));
                assertEquals(1, queryLong(connection,
                        "SELECT count(*) FROM operator_session_family WHERE id = ?", familyId));
                assertEquals(0, queryLong(connection,
                        "SELECT count(*) FROM operator_privileged_action_authorization"));
            }
        });
    }

    @Test
    void constraintsRejectMalformedOrAmbiguousBindings() throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "71").migrate();
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v71-contract@atenea.test");
                UUID familyId = UUID.randomUUID();
                insertFamily(connection, familyId, operatorId);
                execute(connection, authorizationSql(), UUID.randomUUID(), new byte[32],
                        operatorId, familyId, "PUBLISH_RELEASE", new byte[32], new byte[32],
                        "WEBAUTHN");
                assertSqlRejected(connection, authorizationSql(), UUID.randomUUID(), new byte[31],
                        operatorId, familyId, "PUBLISH_RELEASE", new byte[32], new byte[32],
                        "WEBAUTHN");
                assertSqlRejected(connection, authorizationSql(), UUID.randomUUID(), new byte[32],
                        operatorId, familyId, "publish-release", new byte[32], new byte[32],
                        "WEBAUTHN");
                assertSqlRejected(connection, authorizationSql(), UUID.randomUUID(), new byte[32],
                        operatorId, familyId, "PUBLISH_RELEASE", new byte[31], new byte[32],
                        "WEBAUTHN");
                assertSqlRejected(connection, authorizationSql(), UUID.randomUUID(), new byte[32],
                        operatorId, familyId, "PUBLISH_RELEASE", new byte[32], new byte[32],
                        "PASSWORD");
                assertSqlRejected(connection, """
                        INSERT INTO operator_webauthn_challenge (
                            id, challenge_digest, purpose, channel, operator_id,
                            session_family_id, relying_party_id, expected_origin,
                            created_at, expires_at)
                        VALUES (?, ?, 'STEP_UP', 'WEB', ?, ?, 'atenea.test',
                            'https://atenea.test', now(), now() + interval '5 minutes')
                        """, UUID.randomUUID(), new byte[32], operatorId, familyId);
            }
        });
    }

    private String authorizationSql() {
        return """
                INSERT INTO operator_privileged_action_authorization (
                    id, authorization_digest, operator_id, session_family_id,
                    action_kind, target_fingerprint, plan_fingerprint, factor,
                    authenticated_at, credential_version, role_version,
                    created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), 0, 0, now(),
                    now() + interval '5 minutes')
                """;
    }

    private void insertFamily(Connection connection, UUID familyId, long operatorId)
            throws SQLException {
        execute(connection, """
                INSERT INTO operator_session_family (
                    id, operator_id, client_type, device_label, current_generation,
                    created_at, last_used_at, absolute_expires_at,
                    authenticated_at, authentication_method)
                VALUES (?, ?, 'WEB', 'Synthetic browser', 0, now(), now(),
                    now() + interval '30 days', now(), 'pwd')
                """, familyId, operatorId);
    }

    private void withIsolatedSchema(SqlWork work) throws Exception {
        String schema = "v71_" + UUID.randomUUID().toString().replace("-", "");
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
                VALUES (?, 'Synthetic V71 operator', 'synthetic-hash', TRUE, now(), now())
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

    @FunctionalInterface private interface SqlWork { void run(String schema) throws Exception; }
}
