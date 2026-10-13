package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V94ChangeOnlyWorkSessionMigrationTest {
    private static final UUID REAL_CHANGE = UUID.fromString("59315b6e-59bc-4884-9def-356e1ca86ef4");

    @Test void freshDatabaseCanReachTheChangeOnlyModel() throws Exception {
        isolated(schema -> {
            flyway(schema, "94").migrate();
            try (Connection db = connection(schema)) {
                assertEquals("NO", scalar(db, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='work_session' "
                        + "AND column_name='development_change_id'"));
                assertEquals("0", scalar(db, "SELECT count(*) FROM work_session"));
                assertNull(scalar(db, "SELECT to_regclass(current_schema()||'.uk_work_session_active_legacy_project')"));
                assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'.uk_work_session_active_change')"));
            }
        });
    }

    @Test void unboundRowsStopMigrationWithoutAdoptionDeletionOrConstraintChanges() throws Exception {
        isolated(schema -> {
            flyway(schema, "93").migrate();
            try (Connection db = connection(schema)) {
                project(db, 1);
                session(db, 19, 1, null);
                String before = projection(db);
                var error = assertThrows(Exception.class, () -> flyway(schema, "94").migrate());
                assertTrue(error.getMessage().contains("ATENEA_CHANGE_ONLY_CLEANUP_REQUIRED"), error.getMessage());
                assertEquals(before, projection(db));
                assertEquals("0", scalar(db, "SELECT count(*) FROM development_change"));
                assertEquals("YES", scalar(db, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='work_session' "
                        + "AND column_name='development_change_id'"));
                assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'.uk_work_session_active_legacy_project')"));
            }
        });
    }

    @Test void retainedCompletedTicketIsUnchangedAndNewIndependentOrCrossProjectRowsAreRejected() throws Exception {
        isolated(schema -> {
            flyway(schema, "93").migrate();
            try (Connection db = connection(schema)) {
                project(db, 1); project(db, 2);
                change(db, 6, 1, REAL_CHANGE);
                session(db, 21, 1, 6L);
                String before = projection(db);
                assertEquals(1, flyway(schema, "94").migrate().migrationsExecuted);
                assertEquals(before, projection(db));
                assertEquals("23502", assertThrows(SQLException.class, () -> session(db, 22, 1, null)).getSQLState());
                assertEquals("23503", assertThrows(SQLException.class, () -> session(db, 22, 2, 6L)).getSQLState());
                assertEquals("23503", assertThrows(SQLException.class,
                        () -> execute(db, "DELETE FROM development_change WHERE id=6")).getSQLState());
                assertEquals("23502", assertThrows(SQLException.class,
                        () -> execute(db, "UPDATE work_session SET development_change_id=NULL WHERE id=21")).getSQLState());
                assertEquals(before, projection(db));
            }
        });
    }

    @Test void missingSameProjectAuthorityStopsMigrationInsteadOfWeakeningOwnership() throws Exception {
        isolated(schema -> {
            flyway(schema, "93").migrate();
            try (Connection db = connection(schema)) {
                execute(db, "ALTER TABLE work_session DROP CONSTRAINT fk_work_session_development_change_project");
                var error = assertThrows(Exception.class, () -> flyway(schema, "94").migrate());
                assertTrue(error.getMessage().contains("ATENEA_CHANGE_ONLY_PROJECT_BINDING_REQUIRED"), error.getMessage());
                assertEquals("YES", scalar(db, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='work_session' "
                        + "AND column_name='development_change_id'"));
            }
        });
    }

    private void project(Connection db, long id) throws Exception {
        execute(db, "INSERT INTO project(id,name,repo_path,default_base_branch,created_at,updated_at) "
                + "VALUES(" + id + ",'synthetic-v94-" + id + "','/synthetic','main',now(),now())");
    }

    private void change(Connection db, long id, long project, UUID key) throws Exception {
        execute(db, "INSERT INTO development_change(id,change_key,project_id,title,status,base_ref,base_commit,"
                + "workspace_branch,workspace_identity,selected_worker_id,project_policy_revision,"
                + "source_fingerprint_sha256,created_at,updated_at) VALUES("
                + id + ",'" + key + "'," + project + ",'Synthetic completed Apache','COMPLETED','refs/heads/main','"
                + "1".repeat(40) + "','atenea/change-" + key + "','remote:ax42-01:change:" + key
                + "','ax42-01',1,'" + "a".repeat(64) + "',now(),now())");
    }

    private void session(Connection db, long id, long project, Long change) throws SQLException {
        execute(db, "INSERT INTO work_session(id,project_id,development_change_id,status,title,base_branch,"
                + "workspace_branch,workspace_identity,pull_request_status,opened_at,closed_at,last_activity_at,created_at,updated_at) "
                + "VALUES(" + id + "," + project + "," + change + ",'CLOSED','Synthetic retained session','main',"
                + "'atenea/change-" + REAL_CHANGE + "','local:synthetic:" + id + "','NOT_CREATED',now(),now(),now(),now(),now())");
    }

    private String projection(Connection db) throws SQLException {
        return scalar(db, "SELECT jsonb_build_object('sessions',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM work_session s),"
                + "'changes',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM development_change c))::text");
    }

    private static void execute(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getString(1);
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(env("SPRING_DATASOURCE_URL"), env("SPRING_DATASOURCE_USERNAME"),
                env("SPRING_DATASOURCE_PASSWORD")).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load();
    }

    private Connection connection(String schema) throws SQLException {
        Connection db = DriverManager.getConnection(env("SPRING_DATASOURCE_URL"),
                env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD"));
        execute(db, "SET search_path TO \"" + schema + "\""); return db;
    }

    private void isolated(CheckedConsumer work) throws Exception {
        assertTrue(env("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"));
        assertEquals("atenea", env("SPRING_DATASOURCE_USERNAME")); assertEquals("atenea", env("SPRING_DATASOURCE_PASSWORD"));
        String schema = "v94_test_" + UUID.randomUUID().toString().replace("-", "");
        try { work.accept(schema); }
        finally { try (Connection db = connection("public")) { execute(db, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); } }
    }

    private String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Isolated PostgreSQL test requires " + key);
        return value;
    }

    @FunctionalInterface private interface CheckedConsumer { void accept(String schema) throws Exception; }
}
