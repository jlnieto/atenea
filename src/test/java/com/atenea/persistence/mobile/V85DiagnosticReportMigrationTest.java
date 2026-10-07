package com.atenea.persistence.mobile;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V85DiagnosticReportMigrationTest {
    @Test void upgradeFromV84PreservesAccountsAndIsRepeatable() throws Exception {
        isolated(schema -> {
            flyway(schema, "84").migrate();
            try (var connection = connection(schema); var statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO operator_account(id,email,display_name,password_hash,created_at,updated_at)
                        VALUES(9001,'owned@synthetic.atenea.test','Owned migration fixture','synthetic',now(),now())
                        """);
            }
            assertEquals(1, flyway(schema, "85").migrate().migrationsExecuted);
            assertEquals(0, flyway(schema, "85").migrate().migrationsExecuted);
            try (var connection = connection(schema); var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT count(*) FROM operator_account WHERE id=9001")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        });
    }

    @Test void schemaEnforcesOwnershipIdempotencyAndByteLimits() throws Exception {
        isolated(schema -> {
            flyway(schema, "85").migrate();
            try (var connection = connection(schema); var statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO operator_account(id,email,display_name,password_hash,created_at,updated_at) VALUES
                        (9001,'first@synthetic.atenea.test','Owned','synthetic',now(),now()),
                        (9002,'second@synthetic.atenea.test','Owned','synthetic',now(),now())
                        """);
                insert(connection, 9001, 2, "4".repeat(64));
                assertEquals("23505", assertThrows(SQLException.class, () -> insert(connection, 9001, 2, "4".repeat(64))).getSQLState());
                insert(connection, 9002, 2, "4".repeat(64));
                assertEquals("23503", assertThrows(SQLException.class, () -> insert(connection, 9999, 2, "5".repeat(64))).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> insert(connection, 9001, 0, "6".repeat(64))).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> insert(connection, 9001, 4194305, "7".repeat(64))).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> insert(connection, 9001, 3, "8".repeat(64))).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> insert(connection, 9001, 2, "invalid")).getSQLState());
            }
        });
    }

    private void insert(Connection connection, long owner, int size, String checksum) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO mobile_diagnostic_report(id,operator_id,received_at,generated_at,app_version_name,
                    app_version_code,device_model,size_bytes,sha256,report_bytes)
                VALUES(?,?,now(),now(),'synthetic',1,'synthetic',?,?,?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, owner);
            statement.setInt(3, size);
            statement.setString(4, checksum);
            statement.setBytes(5, new byte[] {'{', '}'});
            statement.executeUpdate();
        }
    }
    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),
                        System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).createSchemas(true).locations("classpath:db/migration").target(target).load();
    }
    private Connection connection(String schema) throws SQLException {
        var connection = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        connection.setSchema(schema);
        return connection;
    }
    private void isolated(Work work) throws Exception {
        String schema = "diagnostic_" + UUID.randomUUID().toString().replace("-", "");
        try { work.run(schema); }
        finally {
            try (var connection = connection(schema); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }
    @FunctionalInterface private interface Work { void run(String schema) throws Exception; }
}
