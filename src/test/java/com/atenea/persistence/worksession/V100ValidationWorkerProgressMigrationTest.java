package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V100ValidationWorkerProgressMigrationTest {
    @Test void upgradePreservesExistingEvidenceAndDoesNotInventProgress() throws Exception {
        isolated(schema -> {
            flyway(schema, "99").migrate();
            try (var db = connection(schema)) { seed(db); }
            String before;
            try (var db = connection(schema)) { before = scalar(db, "SELECT to_jsonb(v)::text FROM validation_operation v"); }
            assertEquals(1, flyway(schema, "100").migrate().migrationsExecuted);
            assertEquals(0, flyway(schema, "100").migrate().migrationsExecuted);
            try (var db = connection(schema)) {
                assertEquals(before, scalar(db, "SELECT (to_jsonb(v)-'worker_state'-'worker_observed_at'-'worker_started_at')::text FROM validation_operation v"));
                assertEquals("true", scalar(db, "SELECT (worker_state IS NULL AND worker_observed_at IS NULL AND worker_started_at IS NULL)::text FROM validation_operation"));
                assertEquals("1", scalar(db, "SELECT count(*)::text FROM work_session"));
                assertEquals("1", scalar(db, "SELECT count(*)::text FROM development_change"));
            }
        });
    }

    @Test void rejectsArbitraryOrPartialObservationsButAcceptsClosedBrokerStates() throws Exception {
        isolated(schema -> {
            flyway(schema, "100").migrate();
            try (var db = connection(schema)) {
                seed(db);
                assertEquals("23514", assertThrows(SQLException.class, () -> execute(db,
                        "UPDATE validation_operation SET worker_state='RUNNING'")).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> execute(db,
                        "UPDATE validation_operation SET worker_observed_at=now()")).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> execute(db,
                        "UPDATE validation_operation SET worker_started_at=now()")).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class, () -> execute(db,
                        "UPDATE validation_operation SET worker_state='ARBITRARY',worker_observed_at=now()")).getSQLState());
                execute(db, "UPDATE validation_operation SET worker_state='QUEUED',worker_observed_at=now()");
                assertEquals("QUEUED", scalar(db, "SELECT worker_state FROM validation_operation"));
                execute(db, "UPDATE validation_operation SET worker_state='RUNNING',worker_observed_at=now(),worker_started_at=now()");
                assertEquals("RUNNING", scalar(db, "SELECT worker_state FROM validation_operation"));
            }
        });
    }

    private void seed(Connection db) throws SQLException {
        String key = "00000000-0000-4000-8000-000000000100";
        execute(db, "INSERT INTO project(id,name,repo_path,default_base_branch,created_at,updated_at) VALUES(9001,'Synthetic progress','/synthetic','main',now(),now())");
        execute(db, "INSERT INTO development_change(id,change_key,project_id,title,status,base_ref,base_commit,workspace_branch,workspace_identity,"
                + "selected_worker_id,project_policy_revision,source_fingerprint_sha256,created_at,updated_at) VALUES(9001,'" + key
                + "',9001,'Synthetic progress','COMPLETED','refs/heads/main','" + "1".repeat(40) + "','atenea/change-" + key
                + "','remote:synthetic-worker:change:" + key + "','synthetic-worker',1,'" + "4".repeat(64) + "',now(),now())");
        execute(db, "INSERT INTO work_session(id,project_id,development_change_id,status,title,base_branch,workspace_branch,workspace_identity,"
                + "pull_request_status,opened_at,closed_at,last_activity_at,created_at,updated_at) VALUES(9001,9001,9001,'CLOSED','Synthetic progress','main',"
                + "'atenea/change-" + key + "','remote:synthetic-worker:change:" + key + "','NOT_CREATED',now(),now(),now(),now(),now())");
        execute(db, "INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,definition_revision,"
                + "identity_sha256,summary,started_at,created_at,updated_at) VALUES('00000000-0000-4000-8000-000000000101',9001,'BACKEND_TEST','RUNNING','"
                + "4".repeat(64) + "','synthetic','" + "5".repeat(64) + "','Legacy persisted attempt',now(),now(),now())");
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"), System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")).schemas(schema).defaultSchema(schema).createSchemas(true)
                .locations("classpath:db/migration").target(target).load();
    }
    private Connection connection(String schema) throws SQLException {
        var db = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"), System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
        db.setSchema(schema);
        return db;
    }
    private void isolated(Work work) throws Exception {
        assertTrue(System.getenv("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"));
        assertEquals("atenea", System.getenv("SPRING_DATASOURCE_USERNAME"));
        assertEquals("atenea", System.getenv("SPRING_DATASOURCE_PASSWORD"));
        String schema = "progress_" + UUID.randomUUID().toString().replace("-", "");
        try { work.run(schema); }
        finally { try (var db = connection(schema)) { execute(db, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); } }
    }
    private void execute(Connection db, String sql) throws SQLException { try (var statement = db.createStatement()) { statement.execute(sql); } }
    private String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getString(1); }
    }
    @FunctionalInterface private interface Work { void run(String schema) throws Exception; }
}
