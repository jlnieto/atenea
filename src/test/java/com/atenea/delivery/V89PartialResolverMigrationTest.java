package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Upgrade a synthetic V88 history without fabricating a past HTTP observation. */
class V89PartialResolverMigrationTest {
    private final String schema="partial_resolver_"+UUID.randomUUID().toString().replace("-","");
    private Connection connection() throws SQLException {
        var connection=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        connection.setSchema(schema);return connection;
    }
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"))
            .schemas(schema).defaultSchema(schema).createSchemas(true).locations("classpath:db/migration").target(target).load();
    }
    private void sql(Connection connection,String value) throws SQLException {
        try (var statement=connection.createStatement()) { statement.execute(value); }
    }
    @Test void preservesV88RetryBindingAndExplicitHistoricalObservationAbsence() throws Exception {
        UUID operation=UUID.randomUUID(),retry=UUID.randomUUID(),change=UUID.randomUUID();
        String fingerprint="5".repeat(64),head="2".repeat(40),base="1".repeat(40);
        try {
            flyway("88").migrate();
            try (var connection=connection()) {
                sql(connection,"INSERT INTO project(id,name,repo_path,default_base_branch) VALUES(9001,'Isolated migration','/synthetic','main')");
                sql(connection,"INSERT INTO operator_account(id,email,display_name,password_hash,codex_operations_role,created_at,updated_at) VALUES(9001,'migration@atenea.test','Test','synthetic','PLATFORM_ADMINISTRATOR',now(),now())");
                sql(connection,"INSERT INTO worker_node(id,protocol_version,endpoint,normal_capacity,heavy_capacity) VALUES('synthetic-v89','agent-run-worker/v1','http://synthetic.invalid',1,1)");
                sql(connection,"INSERT INTO work_session(id,project_id,status,title,base_branch,workspace_identity,opened_at,last_activity_at) VALUES(9001,9001,'OPEN','Isolated','main','local:work-session:9001',now(),now())");
                sql(connection,"INSERT INTO session_turn(id,session_id,actor,message_text) VALUES(9001,9001,'ATENEA','Synthetic; never dispatched')");
                for (int id: new int[]{9001,9002}) {
                    sql(connection,"""
                        INSERT INTO agent_run(id,session_id,origin_turn_id,status,target_repo_path,workspace_identity,started_at,finished_at,
                            execution_target,selected_worker_id,dispatch_id,remote_session_id,workload_kind,project_identity,
                            repository_url,repository_branch,repository_commit,manifest_sha256,development_change_key,
                            change_base_commit,change_expected_canonical_commit,change_source_revision,
                            change_source_fingerprint_sha256,change_workspace_ownership_fingerprint_sha256,retry_of_run_id)
                        VALUES (%d,9001,9001,'FAILED','/synthetic','synthetic-workspace',now(),now(),'REMOTE','synthetic-v89',
                            '%s','%s','project-codex-v4','atenea','https://github.com/jlnieto/atenea.git','main','%s','%s','%s',
                            '%s','%s',4,'%s','%s',%s)
                        """.formatted(id,UUID.randomUUID(),UUID.randomUUID(),head,"a".repeat(64),change,base,head,fingerprint,fingerprint,
                            id==9001 ? "NULL" : "9001"));
                }
                sql(connection,"""
                    INSERT INTO mobile_source_update_operation(id,idempotency_key,session_id,operator_id,publication_receipt_sha256,
                        original_source_commit,original_fingerprint_sha256,state,command_json,preparation_json,
                        prepared_revision,resolver_turn_id,resolver_run_id)
                    VALUES ('%s','%s',9001,9001,'%s','%s','%s','FAILED','{}','{}',4,9001,9001)
                    """.formatted(operation,UUID.randomUUID(),"6".repeat(64),head,fingerprint));
                sql(connection,"INSERT INTO mobile_source_resolver_retry(id,operation_id,source_run_id,operator_id) VALUES('"+retry+"','"+operation+"',9001,9001)");
                sql(connection,"UPDATE mobile_source_resolver_retry SET run_id=9002 WHERE id='"+retry+"'");
            }
            assertEquals(1,flyway("89").migrate().migrationsExecuted);
            assertEquals(0,flyway("89").migrate().migrationsExecuted);
            assertEquals(1,flyway("90").migrate().migrationsExecuted);
            assertEquals(0,flyway("90").migrate().migrationsExecuted);
            try (var connection=connection();var statement=connection.createStatement();var rows=statement.executeQuery(
                "SELECT source_revision,observed_fingerprint_sha256,workspace_dirty,observation_json,source_run_id,run_id FROM mobile_source_resolver_retry")) {
                assertTrue(rows.next());assertEquals(4L,rows.getLong(1));assertEquals(fingerprint,rows.getString(2));
                assertTrue(rows.getBoolean(3));assertNull(rows.getString(4));assertEquals(9001L,rows.getLong(5));assertEquals(9002L,rows.getLong(6));
                assertFalse(rows.next());
                assertEquals("55000",assertThrows(SQLException.class,()->sql(connection,
                    "UPDATE mobile_source_resolver_retry SET observed_fingerprint_sha256='"+"f".repeat(64)+"'")).getSQLState());
            }
        } finally {
            try (var connection=connection()) { sql(connection,"DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE"); }
        }
    }
}
