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

class V72WebAuthnCredentialLifecycleMigrationTest {

    @Test
    void migratesEmptySchemaToExpandOnlyCredentialLifecycle() throws Exception {
        withIsolatedSchema(schema -> {
            Flyway flyway = flyway(schema, "72");
            assertEquals(72, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                assertEquals("72", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
                assertTrue(columnExists(connection, "operator_webauthn_credential",
                        "provider_category"));
                assertTrue(columnExists(connection, "operator_webauthn_credential",
                        "provider_provenance"));
                assertTrue(columnExists(connection, "operator_webauthn_credential",
                        "label_ordinal"));
                assertTrue(columnExists(connection, "operator_webauthn_credential",
                        "last_verified_at"));
                assertTrue(indexExists(connection,
                        "idx_operator_webauthn_credential_inventory"));
            }
        });
    }

    @Test
    void upgradePreservesHistoricalCredentialsAsUnknownWithStableOrdinals()
            throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "71").migrate();
            long operatorId;
            UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
            UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
            try (Connection connection = connection(schema)) {
                operatorId = insertOperator(connection, "v72-upgrade@atenea.test");
                insertCredential(connection, first, operatorId, (byte) 1,
                        "2026-08-15T10:00:00Z");
                insertCredential(connection, second, operatorId, (byte) 2,
                        "2026-08-15T10:01:00Z");
            }

            assertEquals(1, flyway(schema, "72").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                assertEquals("UNKNOWN:UNKNOWN:1", queryString(connection, """
                        SELECT provider_category || ':' || provider_provenance || ':'
                               || label_ordinal
                        FROM operator_webauthn_credential WHERE id = ?
                        """, first));
                assertEquals("UNKNOWN:UNKNOWN:2", queryString(connection, """
                        SELECT provider_category || ':' || provider_provenance || ':'
                               || label_ordinal
                        FROM operator_webauthn_credential WHERE id = ?
                        """, second));
                assertEquals(2, queryLong(connection, """
                        SELECT count(*) FROM operator_webauthn_credential
                        WHERE operator_id = ?
                        """, operatorId));
            }
        });
    }

    @Test
    void constraintsRejectAmbiguousProvidersLabelsAndOwnershipBindings()
            throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "72").migrate();
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v72-contract@atenea.test");
                UUID familyId = UUID.randomUUID();
                insertFamily(connection, familyId, operatorId);
                insertCredentialV72(connection, UUID.randomUUID(), operatorId, (byte) 3,
                        "GOOGLE_PASSWORD_MANAGER", "OPERATOR_DECLARED", 1);
                insertCredentialV72(connection, UUID.randomUUID(), operatorId, (byte) 4,
                        "ONE_PASSWORD", "OPERATOR_DECLARED", 2);

                assertSqlRejected(connection, credentialV72Sql(), UUID.randomUUID(),
                        operatorId, repeated((byte) 5), repeated((byte) 5),
                        "INFERRED_FROM_AAGUID", "OPERATOR_DECLARED", 3);
                assertSqlRejected(connection, credentialV72Sql(), UUID.randomUUID(),
                        operatorId, repeated((byte) 6), repeated((byte) 6),
                        "UNKNOWN", "AAGUID_INFERRED", 3);
                assertSqlRejected(connection, credentialV72Sql(), UUID.randomUUID(),
                        operatorId, repeated((byte) 7), repeated((byte) 7),
                        "UNKNOWN", "UNKNOWN", 2);
                execute(connection, ownershipChallengeSql(), UUID.randomUUID(),
                        repeated((byte) 8), operatorId, familyId);
                assertSqlRejected(connection, ownershipChallengeSql(), UUID.randomUUID(),
                        repeated((byte) 9), null, null);
            }
        });
    }

    @Test
    void v73AddsTargetedOwnershipBindingWithoutBreakingLegacyChallenges()
            throws Exception {
        withIsolatedSchema(schema -> {
            flyway(schema, "72").migrate();
            try (Connection connection = connection(schema)) {
                long operatorId = insertOperator(connection, "v73-upgrade@atenea.test");
                UUID familyId = UUID.randomUUID();
                insertFamily(connection, familyId, operatorId);
                execute(connection, ownershipChallengeSql(), UUID.randomUUID(),
                        repeated((byte) 10), operatorId, familyId);
            }

            assertEquals(1, flyway(schema, "73").migrate().migrationsExecuted);
            try (Connection connection = connection(schema)) {
                long operatorId = queryLong(connection,
                        "SELECT id FROM operator_account WHERE email = ?",
                        "v73-upgrade@atenea.test");
                UUID familyId = UUID.fromString(queryString(connection, """
                        SELECT id::text FROM operator_session_family
                        WHERE operator_id = ?
                        """, operatorId));
                execute(connection, ownershipChallengeSql(), UUID.randomUUID(),
                        repeated((byte) 11), operatorId, familyId);
                execute(connection, targetedOwnershipChallengeSql(), UUID.randomUUID(),
                        repeated((byte) 12), operatorId, familyId,
                        "WEBAUTHN_CREDENTIAL_OWNERSHIP",
                        repeated((byte) 13), repeated((byte) 14));
                assertSqlRejected(connection, targetedOwnershipChallengeSql(),
                        UUID.randomUUID(), repeated((byte) 15), operatorId, familyId,
                        "PUBLISH_RELEASE", repeated((byte) 16), repeated((byte) 17));
                assertEquals("73", queryString(connection, """
                        SELECT version FROM flyway_schema_history
                        WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                        """));
            }
        });
    }

    private String credentialV72Sql() {
        return """
                INSERT INTO operator_webauthn_credential (
                    id, operator_id, credential_id, public_key_cose, algorithm,
                    aaguid, sign_count, transports, backup_eligible, backup_state,
                    created_at, provider_category, provider_provenance, label_ordinal)
                VALUES (?, ?, ?, ?, -7, '00000000-0000-0000-0000-000000000000',
                    0, 'internal', TRUE, TRUE, now(), ?, ?, ?)
                """;
    }

    private String ownershipChallengeSql() {
        return """
                INSERT INTO operator_webauthn_challenge (
                    id, challenge_digest, purpose, channel, operator_id,
                    session_family_id, relying_party_id, expected_origin,
                    created_at, expires_at)
                VALUES (?, ?, 'OWNERSHIP', 'WEB', ?, ?, 'atenea.yudri.es',
                    'https://atenea.yudri.es', now(), now() + interval '5 minutes')
                """;
    }

    private String targetedOwnershipChallengeSql() {
        return """
                INSERT INTO operator_webauthn_challenge (
                    id, challenge_digest, purpose, channel, operator_id,
                    session_family_id, relying_party_id, expected_origin,
                    created_at, expires_at, action_kind, target_fingerprint,
                    plan_fingerprint)
                VALUES (?, ?, 'OWNERSHIP', 'WEB', ?, ?, 'atenea.yudri.es',
                    'https://atenea.yudri.es', now(), now() + interval '5 minutes',
                    ?, ?, ?)
                """;
    }

    private void insertCredential(
            Connection connection,
            UUID id,
            long operatorId,
            byte marker,
            String createdAt
    ) throws SQLException {
        execute(connection, """
                INSERT INTO operator_webauthn_credential (
                    id, operator_id, credential_id, public_key_cose, algorithm,
                    aaguid, sign_count, transports, backup_eligible, backup_state,
                    created_at)
                VALUES (?, ?, ?, ?, -7, '00000000-0000-0000-0000-000000000000',
                    0, 'internal', TRUE, TRUE, ?::timestamptz)
                """, id, operatorId, repeated(marker), repeated(marker), createdAt);
    }

    private void insertCredentialV72(
            Connection connection,
            UUID id,
            long operatorId,
            byte marker,
            String category,
            String provenance,
            long ordinal
    ) throws SQLException {
        execute(connection, credentialV72Sql(), id, operatorId,
                repeated(marker), repeated(marker), category, provenance, ordinal);
    }

    private byte[] repeated(byte marker) {
        byte[] value = new byte[32];
        java.util.Arrays.fill(value, marker);
        return value;
    }

    private long insertOperator(Connection connection, String email) throws SQLException {
        return queryLong(connection, """
                INSERT INTO operator_account (
                    email, display_name, password_hash, active, created_at, updated_at)
                VALUES (?, 'Synthetic V72 operator', 'synthetic-hash', TRUE, now(), now())
                RETURNING id
                """, email);
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
        String schema = "v72_" + UUID.randomUUID().toString().replace("-", "");
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

    private void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private long queryLong(Connection connection, String sql, Object... values)
            throws SQLException {
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
            throw new IllegalStateException(name + " is required for isolated migration tests");
        }
        return value;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(String schema) throws Exception;
    }
}
