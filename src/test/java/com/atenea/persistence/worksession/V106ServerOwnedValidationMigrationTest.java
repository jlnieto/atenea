package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V106ServerOwnedValidationMigrationTest {
    @Test void upgradePreservesAttemptsAndDoesNotAuthorizeHistoricalWork() throws Exception {
        isolated(schema->{
            flyway(schema,"105").migrate();
            String before;
            try(var db=connect(schema)) {seed(db);before=scalar(db,"SELECT to_jsonb(v)::text FROM validation_operation v");}
            assertEquals(1,flyway(schema,"106").migrate().migrationsExecuted);
            assertEquals(0,flyway(schema,"106").migrate().migrationsExecuted);
            try(var db=connect(schema)) {
                assertEquals(before,scalar(db,"SELECT to_jsonb(v)::text FROM validation_operation v"));
                assertEquals("0",scalar(db,"SELECT count(*)::text FROM change_validation_workflow"));
                assertEquals("1",scalar(db,"SELECT count(*)::text FROM work_session"));
            }
        });
    }
    @Test void databaseRejectsConcurrentActiveIntentsAndInvalidProgress() throws Exception {
        isolated(schema->{
            flyway(schema,"106").migrate();
            try(var db=connect(schema)) {
                seed(db);
                String insert="INSERT INTO change_validation_workflow(id,session_id,change_key,project_id,source_revision,source_fingerprint_sha256,"
                        +"remote_session_id,worker_id,workspace_identity,workspace_branch,canonical_commit,state,phase) VALUES(";
                String owner=",9001,'00000000-0000-4000-8000-000000000100',9001,3,'"+"4".repeat(64)+"','00000000-0000-4000-8000-000000000102',"
                        +"'synthetic-worker','remote:synthetic-worker:change:00000000-0000-4000-8000-000000000100','atenea/change-00000000-0000-4000-8000-000000000100','"+"1".repeat(40)+"','RUNNING','QUEUED')";
                execute(db,insert+"'00000000-0000-4000-8000-000000000103'"+owner);
                assertEquals("23505",assertThrows(SQLException.class,()->execute(db,insert+"'00000000-0000-4000-8000-000000000104'"+owner)).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->execute(db,"UPDATE change_validation_workflow SET state='SUCCEEDED'")).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->execute(db,"UPDATE change_validation_workflow SET phase='DISPATCHING'")).getSQLState());
                assertEquals("23514",assertThrows(SQLException.class,()->execute(db,"UPDATE change_validation_workflow SET error_code='/tmp/arbitrary'")).getSQLState());
                execute(db,"UPDATE change_validation_workflow SET state='SUCCEEDED',phase='FINISHED',finished_at=now()");
                execute(db,insert+"'00000000-0000-4000-8000-000000000104'"+owner);
                assertEquals("2",scalar(db,"SELECT count(*)::text FROM change_validation_workflow"));
            }
        });
    }
    private void seed(Connection db) throws SQLException {
        execute(db,"INSERT INTO project(id,name,repo_path,default_base_branch,created_at,updated_at) VALUES(9001,'Synthetic workflow','/synthetic','main',now(),now())");
        execute(db,"INSERT INTO development_change(id,change_key,project_id,title,status,base_ref,base_commit,workspace_branch,workspace_identity,"
                +"selected_worker_id,project_policy_revision,source_fingerprint_sha256,created_at,updated_at) VALUES(9001,'00000000-0000-4000-8000-000000000100',"
                +"9001,'Synthetic workflow','COMPLETED','refs/heads/main','"+"1".repeat(40)+"','atenea/change-00000000-0000-4000-8000-000000000100',"
                +"'remote:synthetic-worker:change:00000000-0000-4000-8000-000000000100','synthetic-worker',1,'"+"4".repeat(64)+"',now(),now())");
        execute(db,"INSERT INTO work_session(id,project_id,development_change_id,status,title,base_branch,workspace_branch,workspace_identity,"
                +"pull_request_status,opened_at,closed_at,last_activity_at,created_at,updated_at) VALUES(9001,9001,9001,'CLOSED','Synthetic workflow','main',"
                +"'atenea/change-00000000-0000-4000-8000-000000000100','remote:synthetic-worker:change:00000000-0000-4000-8000-000000000100','NOT_CREATED',now(),now(),now(),now(),now())");
        execute(db,"INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,definition_revision,"
                +"identity_sha256,summary,started_at,created_at,updated_at) VALUES('00000000-0000-4000-8000-000000000101',9001,'BACKEND_TEST','RUNNING','"
                +"4".repeat(64)+"','synthetic','"+"5".repeat(64)+"','Legacy attempt',now(),now(),now())");
    }
    private Flyway flyway(String schema,String target) {
        return Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).createSchemas(true).locations("classpath:db/migration").target(target).load();
    }
    private Connection connect(String schema) throws SQLException {
        var db=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        db.setSchema(schema);return db;
    }
    private void isolated(Work test) throws Exception {
        assertTrue(System.getenv("SPRING_DATASOURCE_URL").matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test"));
        assertEquals("atenea",System.getenv("SPRING_DATASOURCE_USERNAME"));assertEquals("atenea",System.getenv("SPRING_DATASOURCE_PASSWORD"));
        String schema="workflow_"+UUID.randomUUID().toString().replace("-","");
        try {test.run(schema);} finally {try(var db=connect(schema)){execute(db,"DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");}}
    }
    private void execute(Connection db,String sql)throws SQLException {try(var s=db.createStatement()){s.execute(sql);}}
    private String scalar(Connection db,String sql)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    @FunctionalInterface interface Work {void run(String schema)throws Exception;}
}
