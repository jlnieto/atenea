package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class V95IndependentModelContractionTest {
    private static final String[] RETIRED = {"remote_close_legacy_event", "remote_close_legacy_operation",
            "remote_close_legacy_plan", "fresh_work_session_operation", "rescue_session_turn", "rescue_session"};

    @Test void freshDatabaseReachesContractedModelAndFlywayReplayIsNoOp() throws Exception {
        isolated(schema -> {
            flyway(schema, "95").migrate();
            try (var db = connection(schema)) {
                absent(db);
                assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'.work_session_retirement')"));
                assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'.mobile_delivery_operation')"));
                assertEquals("NO", scalar(db, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=current_schema() AND table_name='work_session' AND column_name='development_change_id'"));
                assertEquals("0", scalar(db, "SELECT count(*) FROM flyway_schema_history WHERE NOT success"));
            }
            assertEquals(0, flyway(schema, "95").migrate().migrationsExecuted);
        });
    }

    @Test void rescueDataStopsContractionWithoutDeletionOrPartialDdl() throws Exception {
        isolated(schema -> {
            flyway(schema, "94").migrate();
            try (var db = connection(schema)) {
                retained(db);
                sql(db, "INSERT INTO rescue_session(id,project_id,status,title,opened_at,last_activity_at) "
                        + "VALUES(1,1,'OPEN','Synthetic rescue',now(),now())");
                sql(db, "INSERT INTO rescue_session_turn(rescue_session_id,actor,message_text) VALUES(1,'OPERATOR','Synthetic')");
                String before = scalar(db, "SELECT row_to_json(r)::text FROM rescue_session r");
                var failure = assertThrows(Exception.class, () -> flyway(schema, "95").migrate());
                assertTrue(failure.getMessage().contains("ATENEA_CHANGE_ONLY_LEGACY_DATA_REMAINS"), failure.getMessage());
                assertEquals(before, scalar(db, "SELECT row_to_json(r)::text FROM rescue_session r"));
                assertEquals("1", scalar(db, "SELECT count(*) FROM rescue_session_turn"));
                for (String table : RETIRED) assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'." + table + "')"));
            }
        });
    }

    @Test void legacyPlanStopsContractionEvenWhenAllSessionsAreChangeOwned() throws Exception {
        isolated(schema -> {
            flyway(schema, "94").migrate();
            try (var db = connection(schema)) {
                retained(db);
                sql(db, "INSERT INTO operator_account(id,email,display_name,password_hash,active,created_at,updated_at) "
                        + "VALUES(1,'synthetic@example.invalid','Synthetic','synthetic',true,now(),now())");
                sql(db, "INSERT INTO remote_close_legacy_plan(plan_id,work_session_id,requested_by,idempotency_key,"
                        + "operation,worker_id,project_identity,remote_session_id,workspace_identity,"
                        + "ownership_fingerprint_sha256,request_fingerprint_sha256,expires_at,created_at) "
                        + "VALUES('10000000-0000-4000-8000-000000000001',21,1,'10000000-0000-4000-8000-000000000002',"
                        + "'RECONCILE_REMOTE_CLOSE','ax42-01','atenea','10000000-0000-4000-8000-000000000003',"
                        + "'remote:synthetic','" + "a".repeat(64) + "','" + "b".repeat(64)
                        + "',now()+interval '5 minutes',now())");
                String before = scalar(db, "SELECT row_to_json(p)::text FROM remote_close_legacy_plan p");
                var failure = assertThrows(Exception.class, () -> flyway(schema, "95").migrate());
                assertTrue(failure.getMessage().contains("ATENEA_CHANGE_ONLY_LEGACY_DATA_REMAINS"), failure.getMessage());
                assertEquals(before, scalar(db, "SELECT row_to_json(p)::text FROM remote_close_legacy_plan p"));
                for (String table : RETIRED) assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'." + table + "')"));
            }
        });
    }

    @Test void successorMarkerStopsContractionWithoutClearingTheRetainedRow() throws Exception {
        isolated(schema -> {
            flyway(schema, "94").migrate();
            try (var db = connection(schema)) {
                retained(db);
                sql(db, "UPDATE work_session SET fresh_start_operation_id='15414500-0000-4000-8000-000000000001' WHERE id=21");
                String before = projection(db);
                var failure = assertThrows(Exception.class, () -> flyway(schema, "95").migrate());
                assertTrue(failure.getMessage().contains("ATENEA_CHANGE_ONLY_LEGACY_DATA_REMAINS"), failure.getMessage());
                assertEquals(before, projection(db));
            }
        });
    }

    @Test void cleanupThenContractionPreservesCompletedChangeAndAllCloseGuards() throws Exception {
        isolated(schema -> {
            flyway(schema, "94").migrate();
            try (var db = connection(schema)) {
                retained(db);
                String before = projection(db);
                String guard = scalar(db, "SELECT pg_get_functiondef('enforce_work_session_remote_close_monotonicity()'::regprocedure)");
                assertEquals(1, flyway(schema, "95").migrate().migrationsExecuted);
                absent(db);
                assertEquals(before, projection(db));
                assertEquals(guard.replace("        AND OLD.fresh_start_operation_id IS NOT DISTINCT FROM\n"
                        + "            NEW.fresh_start_operation_id\n", ""),
                        scalar(db, "SELECT pg_get_functiondef('enforce_work_session_remote_close_monotonicity()'::regprocedure)"));
                sql(db, "UPDATE work_session SET title='Synthetic updated retained conversation' WHERE id=21");
                assertEquals("23502", assertThrows(SQLException.class,
                        () -> sql(db, "UPDATE work_session SET development_change_id=NULL WHERE id=21")).getSQLState());
                assertEquals("23514", assertThrows(SQLException.class,
                        () -> sql(db, "UPDATE work_session SET remote_close_state='UNVERIFIED_LEGACY' WHERE id=21")).getSQLState());
            }
        });
    }

    @Test void foreignSchemaDependencyIsNotRemovedByCascade() throws Exception {
        isolated(schema -> {
            flyway(schema, "94").migrate();
            try (var db = connection(schema)) {
                sql(db, "CREATE TABLE unreviewed (id bigint PRIMARY KEY, rescue_id bigint REFERENCES rescue_session(id))");
                var failure = assertThrows(Exception.class, () -> flyway(schema, "95").migrate());
                assertTrue(failure.getMessage().contains("depend"), failure.getMessage());
                for (String table : RETIRED) assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'." + table + "')"));
                assertNotNull(scalar(db, "SELECT to_regclass(current_schema()||'.unreviewed')"));
            }
        });
    }

    private void absent(Connection db) throws Exception {
        for (String table : RETIRED) assertNull(scalar(db, "SELECT to_regclass(current_schema()||'." + table + "')"));
        assertEquals("0", scalar(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() "
                + "AND table_name='work_session' AND column_name IN ('fresh_start_operation_id','replacement_work_session_id')"));
    }
    private void retained(Connection db) throws Exception {
        sql(db, "INSERT INTO project(id,name,repo_path,default_base_branch,created_at,updated_at) VALUES(1,'Atenea','/synthetic','main',now(),now())");
        String key = "59315b6e-59bc-4884-9def-356e1ca86ef4";
        sql(db, "INSERT INTO development_change(id,change_key,project_id,title,status,base_ref,base_commit,workspace_branch,"
                + "workspace_identity,selected_worker_id,project_policy_revision,source_fingerprint_sha256,created_at,updated_at) "
                + "VALUES(6,'" + key + "',1,'Synthetic completed Apache','COMPLETED','refs/heads/main','" + "1".repeat(40)
                + "','atenea/change-" + key + "','remote:ax42-01:change:" + key + "','ax42-01',1,'" + "a".repeat(64) + "',now(),now())");
        sql(db, "INSERT INTO work_session(id,project_id,development_change_id,status,title,base_branch,workspace_branch,"
                + "workspace_identity,pull_request_status,opened_at,closed_at,last_activity_at,created_at,updated_at) "
                + "VALUES(21,1,6,'CLOSED','Synthetic retained session','main','atenea/change-" + key
                + "','local:synthetic:21','NOT_CREATED',now(),now(),now(),now(),now())");
    }
    private String projection(Connection db) throws Exception {
        // The retired nullable column disappears; compare all remaining data.
        return scalar(db, "SELECT jsonb_build_object('sessions',(SELECT jsonb_agg(to_jsonb(s)-'fresh_start_operation_id'-'replacement_work_session_id' ORDER BY id) FROM work_session s),"
                + "'changes',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM development_change c))::text");
    }
    private static void sql(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }
    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) { assertTrue(result.next()); return result.getString(1); }
    }
    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(env("SPRING_DATASOURCE_URL"), env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load();
    }
    private Connection connection(String schema) throws SQLException {
        var db = DriverManager.getConnection(env("SPRING_DATASOURCE_URL"), env("SPRING_DATASOURCE_USERNAME"), env("SPRING_DATASOURCE_PASSWORD"));
        sql(db, "SET search_path TO \"" + schema + "\""); return db;
    }
    private void isolated(CheckedConsumer work) throws Exception {
        assertTrue(env("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"));
        assertEquals("atenea", env("SPRING_DATASOURCE_USERNAME")); assertEquals("atenea", env("SPRING_DATASOURCE_PASSWORD"));
        String schema = "v95_test_" + UUID.randomUUID().toString().replace("-", "");
        try { work.accept(schema); }
        finally { try (var db = connection("public")) { sql(db, "DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); } }
    }
    private String env(String key) { String v = System.getenv(key); if (v == null || v.isBlank()) throw new IllegalStateException(key); return v; }
    @FunctionalInterface private interface CheckedConsumer { void accept(String schema) throws Exception; }
}
